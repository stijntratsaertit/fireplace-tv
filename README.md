# Fireplace TV

An Android TV app for the Google TV Streamer that plays cozy fireplace scenes full screen, with no ads and no YouTube app.

Each scene, like *Jazz at Home* or *Cabin at Home*, searches YouTube on the TV. It picks a random long video (90+ minutes by default) and plays it in the app's own player. When the video ends it moves on to another one, skipping the last few it played, so it's rarely the same twice. Each scene is its own tile in Projectivy. The **Winter at Home** tile shows a menu of all scenes.

## Remote

| Button | Does |
|---|---|
| OK / play-pause | Pause or resume |
| → | Another video for this scene |
| ↑ / ↓ | Previous / next scene |
| ← | Show what's playing |
| Back / Home | Stop |

## Install on the TV

1. On the Streamer, install **Downloader** (by AFTVnews) from the Play Store. Allow it to install unknown apps: Settings → Apps → Security & restrictions → Unknown sources.
2. In Downloader, enter:
   `https://github.com/stijntratsaertit/fireplace-tv/releases/latest/download/fireplace.apk`
3. Install it. The tiles appear in Projectivy; add the ones you want to your favourites.

From a computer instead: turn on developer mode and network debugging on the TV, then run `adb connect <tv-ip>` and `adb install -r fireplace.apk`.

Updates install over the old version, because every build is signed with the same committed key (`sideload.keystore`). It only signs this app and protects nothing else.

## Change your scenes

Edit [`config/scenes.json`](config/scenes.json) (editing on github.com is fine) and commit to `main`. GitHub Actions builds a new APK at the same link.

- `search`: what to search YouTube for. Words like "4k" and "10 hours" help.
- `minMinutes`: skip results shorter than this. Live streams are always allowed.
- `urls` (optional): a list of specific videos or playlists to include, e.g. `["https://www.youtube.com/playlist?list=…"]`.
- `tile`: `true` gives the scene its own launcher tile; `false` keeps it only in the Winter at Home menu.
- `id`: lowercase letters, digits and `_`; keep it the same once installed.
- `color`: the tile banner colour. Banners are drawn automatically from the title and subtitle.

## Google Home

- Opening the app by voice or from a Google Home automation doesn't work: Google understands "open Jazz at Home" and sends it to the TV, but Google TV ignores launch commands for sideloaded apps. Start scenes from the tiles instead.
- `appName` in `config/scenes.json` sets the app's name on the TV.
- The player registers as a normal media session, so "Hey Google, pause" and volume control from the Home app should work.

## How it works and caveats

Videos are found and resolved with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), the library behind NewPipe, and played with Media3 ExoPlayer. Like SmartTube, this bypasses YouTube's own apps and ads, which is against YouTube's terms. When YouTube changes things, playback can break until the library version in `app/build.gradle.kts` is updated.

Stream links expire after a few hours. When that happens mid-video, the app looks the video up again and resumes where it was.
