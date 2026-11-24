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
use std::net::UdpSocket;
use std::path::PathBuf;

use embassy_futures::select::select4;
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
use rs_matter::transport::MATTER_SOCKET_BIND_ADDR;
use rs_matter::utils::select::Coalesce;
use rs_matter::{clusters, devices, root_endpoint, Matter, MATTER_PORT};

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

    let matter = Matter::new(&TEST_DEV_DET, TEST_DEV_COMM, &TEST_DEV_ATT, MATTER_PORT);
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

    let socket = async_io::Async::<UdpSocket>::bind(MATTER_SOCKET_BIND_ADDR)?;
    let mut mdns = pin!(mdns::run_mdns(&matter, &crypto));
    let mut transport = pin!(matter.run(&crypto, &socket, &socket, &socket));

    let all = select4(&mut transport, &mut mdns, &mut respond, &mut im_job).coalesce();
    futures_lite::future::block_on(all)
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

