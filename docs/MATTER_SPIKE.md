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

## Update: one Google Home switch per scene (build 2.1.111+)

Each scene is now its own Matter node (On/Off Plug-in Unit) with its own process, storage, port, discriminator, passcode and mDNS host name. Every node uses VID `0xFFF1` / PID `0x8001`, so one Developer Console integration covers them all.

- **Slots:** each scene gets a fixed slot on first use, stored in the `matter_slot_<id>` prefs. Slot 0 is `fireplace`.
  - discriminator = 3840 + slot
  - preferred port = 5541 + slot, falling back to the first free port in 5541..5580
  - mDNS host = `57484F4D45` + slot (2 hex digits)
  - passcode from the list in `MatterService.PASSCODES` (slot 0 = 20202021)
- **Migration:** the previously paired single node, stored in `filesDir/matter/*`, moves to `filesDir/matter/fireplace` and keeps its discriminator 3840 and passcode 20202021. Its manual code stays `3497-011-2332`, so it shouldn't need re-pairing.
- **Which nodes run:**
  - only paired nodes (prefs `matter_paired`)
  - plus a node while it is being added from the menu; the binary runs with `--pair` and opens the commissioning window only then
  - closing the pairing dialog before pairing completes stops that node
- **Behaviour:**
  - Switching a scene ON plays it, and every other node reports OFF.
  - Switching the playing scene OFF calls `finishAndRemoveTask`, so the TV returns to where it was. OFF on a scene that isn't playing is ignored.
  - When the player closes, all nodes report OFF.
- **Menu:** the "Google Home" section lists every scene with its status.
  - OK on a scene that isn't added starts pairing and shows its QR code and manual code.
  - OK on a paired scene offers "Remove", which stops the node and deletes its storage. Also remove it in the Google Home app.
- **`/ping`:** reports the overlay permission and the playing scene, then one line per scene with slot, port, running, commissioned, pairing, on and code.
- **Fixes:** the ON/OFF flapping, `paired=false` in `/ping`, and OFF returning to the menu instead of HOME.
- **Testing the codes without a network:** `fireplace-matter <dir> --name X --discriminator N --passcode N --print-codes` prints the QR and pairing code and exits.

## Update: wake the TV on ON (build 2.1.112+)

- **Wake on ON:** when a plug switches ON, `MatterService.launchScene` wakes the device.
  - If `PowerManager.isInteractive` is false, it takes a 5 s `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, and logs `interactive` before and after.
  - `PlayerActivity` calls `setShowWhenLocked(true)` and `setTurnScreenOn(true)`.
  - With HDMI-CEC on, waking the Streamer turns the TV on and switches the input to it.
- **Permission:** the manifest declares `android.permission.TURN_SCREEN_ON`. If waking doesn't work, try `adb shell appops set app.fireplace TURN_SCREEN_ON allow`.
- **Not done:** putting the device back to sleep on OFF. That needs `DEVICE_POWER`, which only system apps can get.

## Update: removing a scene from Google Home (build 2.1.113+)

- Stopping a node can no longer crash the app. The output reader catches `IOException`/`InterruptedIOException`, and the wait thread catches `InterruptedException`.
- When a paired node reports `FIREPLACE_COMMISSIONED no` (removed in the Google Home app), it is forgotten completely: stopped, its `filesDir/matter/<scene>` deleted, and dropped from `matter_paired`. The menu then shows it as "Not in Google Home", and it isn't restarted on launch.

## Update: fire stays on screen when waking from standby (build 2.1.115+)

- **Cause:** `PlayerActivity.onStop()` always called `finish()`, which is how Home or TV-off stopped the fire. Waking from standby stops the activity briefly during the screen-on/keyguard transition, so it finished itself about 300 ms after starting and the menu underneath came to the front.
- **Fixes:**
  1. Within 8 s of a (re)launch, `onStop` keeps the player: it pauses playback and resumes in `onStart`. After that, `onStop` finishes as before (Home, TV off, another app). Back and OFF from Google Home always finish.
  2. Launches from Google Home use `FLAG_ACTIVITY_CLEAR_TASK` when no player is showing, so the player starts in a clean task and no leftover `MainActivity` sits underneath.
  3. Safety net: 5 s after an ON, if that scene is still on but its player isn't showing, it is launched once more (logged).
  4. Lifecycle logging: `Player: onCreate/onNewIntent/onStart/onResume/onPause/onStop/onDestroy` lines, each with the reason, under tag `FireplaceSpike`.

## Update: Piano filter and logging the pick (build 2.1.117+)

- **Piano at Home** also excludes jazz, saxophone and sax, so "Piano Jazz" videos stay in Jazz at Home.
- **Fire at Home and Jazz at Home are unchanged from 2.1.114.** Campfire and outdoor fire videos are fine there. Build 2.1.116 briefly excluded them; that was reverted.
- **Logging:** the player logs `Player: picked for <scene> from N candidates (M not played recently): <title> <url>` and then `Player: playing <scene>: <title> <url> (<stream type>)` under `FireplaceSpike`.
