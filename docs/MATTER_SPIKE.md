# Matter spike: handoff for testing on the TV

Test build that makes Winter at Home a Google Home smart plug.

- **APK:** https://github.com/stijntratsaertit/fireplace-tv/releases/download/matter-spike/fireplace-spike.apk
- **Release:** tag `matter-spike` (pre-release). The normal `latest` download is untouched.
- **Package:** `app.fireplace`, versionCode 108 or newer, signed `CN=Fireplace sideload`, so it installs over the current version.
- **Source:** branch `matter-spike`. The Rust crate is in `/matter`; the Android side is in `app/src/main/java/app/fireplace/MatterService.kt`.

## Deviation from the requested plan

The request was for connectedhomeip's `virtual-device-app`. This build uses **rs-matter 0.4.1** instead: Project CHIP's Rust Matter stack, which lists Google Home among its tested controllers. It is cross-compiled for `armv7-linux-androideabi` and embedded in `app.fireplace`, so the requested spikes A (device commissioning) and B (background launch) are a single APK.

## Matter device

| | |
|---|---|
| Device type | On/Off Plug-in Unit (`0x010A`), single endpoint 1 (OnOff with LT feature, Groups, Descriptor) |
| Vendor / product ID | `0xFFF1` / `0x8001` (CSA test values; test DAC/PAI built in), "ACME" / "ACME Test" |
| Passcode / discriminator | `20202021` / `3840`; discovery capability IP (on-network, no BLE) |
| Manual pairing code | `3497-011-2332` |
| QR payload | `MT:-24J0AFN00KA064IJ3P0WISA0DK5N1K8SQ1RYCU1O0` |
| Ports | Matter UDP: first free port from 5541 (5540 is taken by GMS's own Matter hub), logged as `FIREPLACE_PORT <n>` and advertised over mDNS; mDNS UDP 5353 (rs-matter built-in responder, `SO_REUSEADDR` + `SO_REUSEPORT`) |

The mDNS responder picks the interface with both an IPv4 address and an IPv6 link-local address, which should be `wlan0`. If Google Home can't find the device, check first for a conflict on 5353 with the system mDNS responder.

**Google Home Developer Console:** add a Matter integration with test VID `0xFFF1`, PID `0x8001` and device type Plug/Outlet. Commission from the same Google account.

## Android side

- **`MatterService`** is a foreground service of type `connectedDevice`. It starts when the app opens, on `BOOT_COMPLETED` and on `MY_PACKAGE_REPLACED`.
  - It holds a `WifiManager.MulticastLock`.
  - It runs `nativeLibraryDir/libfireplace_matter.so` (with `extractNativeLibs=true`) with storage in `filesDir/matter`, and restarts it with backoff if it exits.
- **Plug ON** opens `PlayerActivity` with scene pref `google_home_scene`, falling back to `last_scene`, then the first scene.
- **Plug OFF** finishes the player.
- **Sync back:** the player writes `on`/`off` to the plug's stdin, so Google Home's switch matches the TV.
- **Starting the player from the background needs `SYSTEM_ALERT_WINDOW`:**
  `adb shell appops set app.fireplace SYSTEM_ALERT_WINDOW allow`
- **Logcat tag** `FireplaceSpike`. The plug's output, including rs-matter's own logs, is prefixed `plug: `.
- **Test HTTP endpoint on port 8765:**
  - `/ping`: uptime, whether the plug is running, paired state, overlay permission
  - `/launch?scene=<id>`
  - `/stop`
  - `/log`: the last 200 lines
- **Main menu:** a new "Google Home" section with a pair row (OK shows the QR code), the scene Google Home starts, and the overlay-permission row.
- **Scene ids:** `fireplace`, `fireplace_jazz`, `fireplace_piano`, `fireplace_christmas`, `cabin_snow`, `winter_window`

## What was verified, and what wasn't

**Verified:**
- the APK contents, including the ARM ELF binary at `lib/armeabi-v7a/libfireplace_matter.so`
- the manifest: FGS type `0x10` and the permissions
- the signature
- the pairing codes, by running the plug on x86

**Not verified:** anything on a real device or network, because the build environment has no IPv6 or Android device.

When testing on the TV, report logcat (tag `FireplaceSpike`) covering startup, mDNS and commissioning, together with the `/ping` output.
