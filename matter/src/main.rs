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
//! Usage: fireplace-matter <storage-dir>
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
use rs_matter::dm::devices::test::{DAC_PRIVKEY, TEST_DEV_ATT, TEST_DEV_COMM, TEST_DEV_DET};
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
use rs_matter::{clusters, devices, root_endpoint, Matter};

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

    let storage: PathBuf = std::env::args()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("fireplace-matter"));
    std::fs::create_dir_all(&storage)?;
    info!("Storing Matter state in {}", storage.display());

    // The TV's own Google Home hub already uses the standard Matter port (5540), so take the
    // first free port from 5541. The port is advertised over mDNS, so controllers still find us.
    let (socket, port) = bind_matter_socket()?;
    info!("Matter transport bound to UDP port {port}");
    println!("FIREPLACE_PORT {port}");

    let matter = Matter::new(&TEST_DEV_DET, TEST_DEV_COMM, &TEST_DEV_ATT, port);
    let store = DirKvBlobStore::new(storage);
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
        matter.print_standard_qr_text(DiscoveryCapabilities::IP)?;
        matter.print_standard_qr_code(QrTextType::Unicode, DiscoveryCapabilities::IP)?;
        matter.open_basic_comm_window(MAX_COMM_WINDOW_TIMEOUT_SECS, &crypto, &())?;
    }
    std::io::stdout().flush().ok();

    let mut mdns = pin!(mdns::run_mdns(&matter, &crypto));
    let mut transport = pin!(matter.run(&crypto, &socket, &socket, &socket));

    let mut watch = pin!(watch_commissioning(&matter, &crypto));

    let all = select(
        select4(&mut transport, &mut mdns, &mut respond, &mut im_job).coalesce(),
        &mut watch,
    )
    .coalesce();
    futures_lite::future::block_on(all)
}

/// Tells the app when the device gets paired or unpaired, and reopens pairing after it's
/// removed from Google Home so it can be added again.
async fn watch_commissioning<C: Crypto>(matter: &Matter<'_>, crypto: &C) -> Result<(), Error> {
    let mut paired = matter.has_fabrics();
    loop {
        async_io::Timer::after(std::time::Duration::from_secs(2)).await;
        let now = matter.has_fabrics();
        if now != paired {
            paired = now;
            println!("FIREPLACE_COMMISSIONED {}", if now { "yes" } else { "no" });
            std::io::stdout().flush().ok();
            if !now {
                info!("Removed from all controllers; opening pairing again");
                matter.open_basic_comm_window(MAX_COMM_WINDOW_TIMEOUT_SECS, crypto, &())?;
            }
        }
    }
}

/// Binds the Matter UDP socket (dual-stack, all interfaces) to the first free port in 5541..=5560.
fn bind_matter_socket() -> Result<(async_io::Async<UdpSocket>, u16), Error> {
    let mut last_err = None;
    for port in 5541..=5560u16 {
        let addr = SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::UNSPECIFIED, port, 0, 0));
        match async_io::Async::<UdpSocket>::bind(addr) {
            Ok(socket) => return Ok((socket, port)),
            Err(e) => {
                log::warn!("Matter port {port} unavailable: {e}");
                last_err = Some(e);
            }
        }
    }
    log::error!("No free Matter port in 5541..=5560");
    Err(last_err.expect("at least one port was tried").into())
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

