//! On/off logic of the plug: tells the Android app when Google Home switches it, and
//! listens for the app saying the fire was stopped or started with the remote, so the
//! switch in Google Home stays in sync.
//!
//! Protocol with the app, one line at a time:
//!   stdout:  FIREPLACE_ONOFF on | FIREPLACE_ONOFF off
//!   stdin:   on | off

use std::io::{BufRead, Write as _};
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::sync::Mutex as StdMutex;
use std::time::Duration;

use log::info;

use rs_matter::dm::clusters::app::on_off::{
    EffectVariantEnum, OnOffHooks, OutOfBandMessage, StartUpOnOffEnum,
};
use rs_matter::dm::clusters::decl::on_off as on_off_cluster;
use rs_matter::dm::clusters::decl::on_off::Feature;
use rs_matter::dm::Cluster;
use rs_matter::error::Error;
use rs_matter::tlv::Nullable;
use rs_matter::utils::cell::RefCell;
use rs_matter::utils::sync::blocking::Mutex;
use rs_matter::with;

struct State {
    on_off: bool,
    start_up_on_off: Option<StartUpOnOffEnum>,
}

pub struct PlugLogic {
    state: Mutex<RefCell<State>>,
    from_app: StdMutex<Option<Receiver<bool>>>,
}

impl PlugLogic {
    pub fn new() -> Self {
        // Read "on"/"off" lines from the app on a plain thread; the Matter loop polls them.
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            for line in std::io::stdin().lock().lines() {
                let Ok(line) = line else { break };
                match line.trim() {
                    "on" => {
                        let _ = tx.send(true);
                    }
                    "off" => {
                        let _ = tx.send(false);
                    }
                    _ => {}
                }
            }
        });

        Self {
            state: Mutex::new(RefCell::new(State {
                on_off: false,
                // After a restart the fire stays off until asked for.
                start_up_on_off: Some(StartUpOnOffEnum::Off),
            })),
            from_app: StdMutex::new(Some(rx)),
        }
    }
}

impl OnOffHooks for PlugLogic {
    // Same cluster shape as rs-matter's reference On/Off implementation (Lighting feature
    // included, which the On/Off Plug-in Unit device type also requires).
    const CLUSTER: Cluster<'static> = on_off_cluster::FULL_CLUSTER
        .with_revision(6)
        .with_features(Feature::LIGHTING.bits())
        .with_attrs(with!(
            required;
            on_off_cluster::AttributeId::OnOff
                | on_off_cluster::AttributeId::GlobalSceneControl
                | on_off_cluster::AttributeId::OnTime
                | on_off_cluster::AttributeId::OffWaitTime
                | on_off_cluster::AttributeId::StartUpOnOff
        ))
        .with_cmds(with!(
            on_off_cluster::CommandId::Off
                | on_off_cluster::CommandId::On
                | on_off_cluster::CommandId::Toggle
                | on_off_cluster::CommandId::OffWithEffect
                | on_off_cluster::CommandId::OnWithRecallGlobalScene
                | on_off_cluster::CommandId::OnWithTimedOff
        ));

    fn on_off(&self) -> bool {
        self.state.lock(|s| s.borrow().on_off)
    }

    fn set_on_off(&self, on: bool) {
        let changed = self.state.lock(|s| {
            let mut s = s.borrow_mut();
            let changed = s.on_off != on;
            s.on_off = on;
            changed
        });
        if changed {
            info!("Plug switched {}", if on { "on" } else { "off" });
            println!("FIREPLACE_ONOFF {}", if on { "on" } else { "off" });
            std::io::stdout().flush().ok();
        }
    }

    fn start_up_on_off(&self) -> Nullable<StartUpOnOffEnum> {
        match self.state.lock(|s| s.borrow().start_up_on_off) {
            Some(v) => Nullable::some(v),
            None => Nullable::none(),
        }
    }

    fn set_start_up_on_off(&self, value: Nullable<StartUpOnOffEnum>) -> Result<(), Error> {
        self.state
            .lock(|s| s.borrow_mut().start_up_on_off = value.into_option());
        Ok(())
    }

    async fn handle_off_with_effect(&self, _effect: EffectVariantEnum) {}

    async fn run<F: Fn(OutOfBandMessage)>(&self, notify: F) {
        let rx = self.from_app.lock().unwrap().take();
        let Some(rx) = rx else {
            return core::future::pending::<()>().await;
        };
        loop {
            async_io::Timer::after(Duration::from_millis(300)).await;
            loop {
                match rx.try_recv() {
                    Ok(on) => {
                        // Update the Matter state without echoing a command back to the app.
                        self.state.lock(|s| s.borrow_mut().on_off = on);
                        info!("App reports the fire is {}", if on { "on" } else { "off" });
                        notify(OutOfBandMessage::Update);
                    }
                    Err(TryRecvError::Empty) => break,
                    Err(TryRecvError::Disconnected) => return core::future::pending::<()>().await,
                }
            }
        }
    }
}
