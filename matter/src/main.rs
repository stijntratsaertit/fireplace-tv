//! A Matter "On/Off Plug-in Unit" that runs on the TV inside the Winter at Home app.
//!
//! Google Home commissions it like any smart plug. Switching it on or off prints a line
//! to stdout, which the Android app reads to start or stop the fire:
//!
//!   FIREPLACE_ONOFF on
//!   FIREPLACE_ONOFF off
//!
//! The pairing details are logged as `SetupQRCode: [MT:...]` and `PairingCode: [...]`.
//!
//! The app runs one of these per scene, so each scene is its own switch in Google Home:
//!
//!   fireplace-matter <storage-dir> --name "Jazz at Home" --discriminator 3841
//!                    --passcode 20202028 --port 5542 --host 4A415A5A0001 [--pair]
//!
//! Pairing is only possible while started with `--pair` (and not yet paired).
//!
//! Based on rs-matter's `onoff_light` example (Apache-2.0, Project CHIP Authors).

use core::pin::pin;

use std::io::Write as _;
use std::net::{Ipv6Addr, SocketAddr, SocketAddrV6, UdpSocket};
use std::path::PathBuf;

use embassy_futures::select::{select, select4};
use log::info;
use rand::Rng;

use rs_matter::crypto::{default_crypto, Crypto};
use rs_matter::dm::clusters::app::level_control::LevelControlHooks;
use rs_matter::dm::clusters::app::on_off::{self, NoLevelControl, OnOffHooks};
use rs_matter::dm::clusters::desc::{self, ClusterHandler as _};
use rs_matter::dm::clusters::groups::{self, ClusterHandler as _};
use rs_matter::dm::clusters::basic_info::BasicInfoConfig;
use rs_matter::dm::devices::test::{DAC_PRIVKEY, TEST_DEV_ATT, TEST_DEV_DET};
use rs_matter::dm::endpoints;
use rs_matter::dm::networks::eth::EthNetwork;
use rs_matter::dm::networks::SysNetifs;
use rs_matter::dm::{Async, DataModel, Dataver, DeviceType, Endpoint, Node};
use rs_matter::error::Error;
use rs_matter::im::{EthInteractionModelState, InteractionModel};
use rs_matter::pairing::qr::QrTextType;
use rs_matter::pairing::DiscoveryCapabilities;
use rs_matter::persist::DirKvBlobStore;
use rs_matter::respond::DefaultResponder;
use rs_matter::sc::pase::MAX_COMM_WINDOW_TIMEOUT_SECS;
use rs_matter::transport::exchange::MatterBuffers;
use rs_matter::utils::select::Coalesce;
use rs_matter::crypto::{CryptoSensitive, CryptoSensitiveRef};
use rs_matter::{clusters, devices, root_endpoint, BasicCommData, Matter};

mod mdns;
mod plug;

use plug::PlugLogic;

/// Matter "On/Off Plug-in Unit". A plug rather than a light, so that
/// "Hey Google, turn off the lights" doesn't put out the fire.
const DEV_TYPE_ON_OFF_PLUG_IN_UNIT: DeviceType = DeviceType {
    dtype: 0x010A,
    drev: 3,
};

fn main() -> Result<(), Error> {
    env_logger::init_from_env(
        env_logger::Env::default().filter_or(env_logger::DEFAULT_FILTER_ENV, "info"),
    );

    let args = Args::parse();
    std::fs::create_dir_all(&args.storage)?;
    info!("{}: storing Matter state in {}", args.name, args.storage.display());

    // The TV's own Google Home hub already uses the standard Matter port (5540), so each scene
    // gets its own port from 5541 (falling back to the next free one). The port is advertised
    // over mDNS, so controllers still find it.
    let (socket, port) = if args.print_codes {
        (None, args.port)
    } else {
        let (socket, port) = bind_matter_socket(args.port)?;
        (Some(socket), port)
    };
    info!("Matter transport bound to UDP port {port}");
    println!("FIREPLACE_PORT {port}");

    // Same test vendor/product as before (so one Developer Console integration covers every
    // scene), but each scene has its own name, serial, discriminator and passcode.
    let name: &'static str = Box::leak(args.name.clone().into_boxed_str());
    let serial: &'static str = Box::leak(format!("winter-{}", args.host).into_boxed_str());
    let dev_det: &'static BasicInfoConfig<'static> = Box::leak(Box::new(BasicInfoConfig {
        product_name: name,
        product_label: name,
        device_name: name,
        vendor_name: "Winter at Home",
        serial_no: serial,
        unique_id: serial,
        ..TEST_DEV_DET
    }));
    let comm = BasicCommData {
        // The setup passcode, as rs-matter stores it (4 bytes, little-endian).
        password: CryptoSensitive::<4>::new_from_ref(CryptoSensitiveRef::new(&args.passcode.to_le_bytes())),
        discriminator: args.discriminator,
    };
    let host: &'static str = Box::leak(args.host.clone().into_boxed_str());

    let matter = Matter::new(dev_det, comm, &TEST_DEV_ATT, port);
    if args.print_codes {
        matter.print_standard_qr_text(DiscoveryCapabilities::IP)?;
        matter.print_standard_qr_code(QrTextType::Unicode, DiscoveryCapabilities::IP)?;
        return Ok(());
    }
    let store = DirKvBlobStore::new(args.storage.clone());
    let buffers: MatterBuffers = MatterBuffers::new();
    let state: EthInteractionModelState = EthInteractionModelState::new(EthNetwork::new_default());
    let kv = matter.kv(store);
    matter.startup(&kv)?;

    let crypto = default_crypto(rand::rng(), DAC_PRIVKEY);
    let mut rand = crypto.rand()?;

    let on_off_handler = on_off::OnOffHandler::<_, NoLevelControl>::new(
        Dataver::new_rand(&mut rand),
        1,
        PlugLogic::new(),
    );
    on_off_handler.init(None);

    let im = InteractionModel::new(
        &matter,
        &crypto,
        &buffers,
        data_model(rand, &on_off_handler),
        &kv,
        &state,
    );
    futures_lite::future::block_on(im.startup())?;

    let responder = DefaultResponder::new(&im);
    let mut respond = pin!(responder.run::<4, 4>());
    let mut im_job = pin!(im.run());

    // Print the pairing details first, so the app can show them even if networking fails.
    if matter.has_fabrics() {
        println!("FIREPLACE_COMMISSIONED yes");
    } else {
        println!("FIREPLACE_COMMISSIONED no");
        if args.pair {
            matter.print_standard_qr_text(DiscoveryCapabilities::IP)?;
            matter.print_standard_qr_code(QrTextType::Unicode, DiscoveryCapabilities::IP)?;
            matter.open_basic_comm_window(MAX_COMM_WINDOW_TIMEOUT_SECS, &crypto, &())?;
            println!("FIREPLACE_PAIRING open");
        }
    }
    std::io::stdout().flush().ok();

    let mut mdns = pin!(mdns::run_mdns(&matter, &crypto, host));
    let socket = socket.expect("bound unless --print-codes");
    let mut transport = pin!(matter.run(&crypto, &socket, &socket, &socket));

    let mut watch = pin!(watch_commissioning(&matter));

    let all = select(
        select4(&mut transport, &mut mdns, &mut respond, &mut im_job).coalesce(),
        &mut watch,
    )
    .coalesce();
    futures_lite::future::block_on(all)
}

/// Tells the app when the device gets paired or unpaired.
async fn watch_commissioning(matter: &Matter<'_>) -> Result<(), Error> {
    let mut paired = matter.has_fabrics();
    loop {
        async_io::Timer::after(std::time::Duration::from_secs(2)).await;
        let now = matter.has_fabrics();
        if now != paired {
            paired = now;
            println!("FIREPLACE_COMMISSIONED {}", if now { "yes" } else { "no" });
            std::io::stdout().flush().ok();
        }
    }
}

/// Binds the Matter UDP socket (dual-stack, all interfaces) to the preferred port, or else
/// the first free one in 5541..=5580.
fn bind_matter_socket(preferred: u16) -> Result<(async_io::Async<UdpSocket>, u16), Error> {
    let mut last_err = None;
    for port in std::iter::once(preferred).chain(5541..=5580u16) {
        let addr = SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::UNSPECIFIED, port, 0, 0));
        match async_io::Async::<UdpSocket>::bind(addr) {
            Ok(socket) => return Ok((socket, port)),
            Err(e) => {
                log::warn!("Matter port {port} unavailable: {e}");
                last_err = Some(e);
            }
        }
    }
    log::error!("No free Matter port in 5541..=5580");
    Err(last_err.expect("at least one port was tried").into())
}

/// Command-line options; see the top of this file.
struct Args {
    storage: PathBuf,
    name: String,
    discriminator: u16,
    passcode: u32,
    port: u16,
    host: String,
    pair: bool,
    /// Print the QR and pairing code, then exit (for checking a scene's codes).
    print_codes: bool,
}

impl Args {
    fn parse() -> Self {
        let mut args = Args {
            storage: std::env::temp_dir().join("fireplace-matter"),
            name: "Fireplace".into(),
            discriminator: 3840,
            passcode: 20202021,
            port: 5541,
            host: "001122334455".into(),
            pair: false,
            print_codes: false,
        };
        let mut it = std::env::args().skip(1);
        while let Some(a) = it.next() {
            match a.as_str() {
                "--name" => args.name = it.next().unwrap_or_default(),
                "--discriminator" => args.discriminator = it.next().and_then(|v| v.parse().ok()).unwrap_or(3840),
                "--passcode" => args.passcode = it.next().and_then(|v| v.parse().ok()).unwrap_or(20202021),
                "--port" => args.port = it.next().and_then(|v| v.parse().ok()).unwrap_or(5541),
                "--host" => args.host = it.next().unwrap_or_default(),
                "--pair" => args.pair = true,
                "--print-codes" => args.print_codes = true,
                other => args.storage = PathBuf::from(other),
            }
        }
        args
    }
}

const NODE: Node<'static> = Node {
    endpoints: &[
        root_endpoint!(eth),
        Endpoint::new(
            1,
            devices!(DEV_TYPE_ON_OFF_PLUG_IN_UNIT),
            clusters!(
                desc::DescHandler::CLUSTER,
                groups::GroupsHandler::CLUSTER,
                PlugLogic::CLUSTER
            ),
        ),
    ],
};

fn data_model<'a, OH: OnOffHooks, LH: LevelControlHooks>(
    mut rand: impl Rng + Copy,
    on_off: &'a on_off::OnOffHandler<'a, OH, LH>,
) -> impl DataModel + 'a {
    (
        NODE,
        endpoints::EthSysHandlerBuilder::new()
            .netif_diag(&SysNetifs)
            .build(rand)
            .chain(
                |e, c| e == 1 && c == desc::DescHandler::CLUSTER.id,
                Async(desc::DescHandler::new(Dataver::new_rand(&mut rand)).adapt()),
            )
            .chain(
                |e, c| e == 1 && c == groups::GroupsHandler::CLUSTER.id,
                Async(groups::GroupsHandler::new(Dataver::new_rand(&mut rand)).adapt()),
            )
            .chain(
                |e, c| e == 1 && c == PlugLogic::CLUSTER.id,
                on_off::HandlerAsyncAdaptor(on_off),
            ),
    )
}

