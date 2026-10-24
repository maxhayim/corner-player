# Corner Player

Picture in picture for the TV: your own camera (a baby monitor, the front door) or a stream from your own M3U
playlist plays in a small window in a corner, over whatever you're watching. It is for your own cameras and
streams: it comes with no channels and points to none, and it only plays the addresses you give it. The window never takes the remote's keys, so the app below
keeps working as before, and it is silent unless you turn its sound on. For the
[Clean Your TV](https://github.com/Gathin23/cleanyourtv) launcher it declares a [module card](https://github.com/Gathin23/cleanyourtv/blob/main/docs/modules.md): once you answer
**Show card** its tile reads "Corner Player" with an orange glow. On any other Android TV home it is an ordinary
app.

![Corner Player on a TV, playing a test stream over Home](screenshot.jpg)

<sub>The video in the corner is a public HLS test stream of [Big Buck Bunny](https://peach.blender.org/) (© Blender Foundation, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)). App banners belong to their owners.</sub>

Open the card and press OK on a camera or channel: it starts in the corner and you're back to what you were
watching. Open the card again to switch, or press **Stop**.

| Row | What it does |
|---|---|
| **Stop** | Closes the corner window (shown while one plays, focused first). |
| **Cameras** | Your cameras by name; OK plays one. **Add a camera** asks for a name and an `rtsp://` or `http(s)://` address; **Remove cameras** (OK twice) removes them all. |
| **Channels** | The channels of your playlist; OK plays one. **Playlist address** is an M3U playlist, the kind [TV Hub](https://github.com/Gathin23/cleanyourtv/tree/main/addons/tv-hub) reads. |
| **Corner** | Bottom right (default), bottom left, top left, top right. |
| **Size** | 25, 33 (default) or 40 % of the screen's width, 16:9. |
| **Sound** | Off (default) or On. |

Android TV's own picture in picture only works for apps that support it themselves; this plays its own sources
instead, so it works over any app, Live TV or an HDMI input.

## Setup

Cameras usually publish RTSP: look for "RTSP" in the camera's app or manual. A sub-stream (smaller picture) is the
better choice for a corner, for example `rtsp://user:password@192.168.1.20:554/stream2`. Type the address on the
TV, or push cameras and the playlist from a computer as a file, so camera passwords are never typed on the TV or
put on a command line:

```bash
cat > backup/corner-player.json <<'JSON'
{"cameras": [{"name": "Nursery", "url": "rtsp://user:password@192.168.1.20:554/stream2"}],
 "playlist": "http://192.168.1.5:8080/playlist.m3u"}
JSON
TV_HOST=<tv ip> ./install.sh backup/corner-player.json
```

The app takes the file into its own storage and deletes it. Keep the file out of git (`backup/` is
git-ignored). On screen, addresses show only the scheme, host and port: never the user name or password in them.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | The cameras and playlist you add, and nothing else: no telemetry, no update checks, no ads. |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | To draw the corner window over other apps. It never takes focus or the remote's keys. Granted by `install.sh`, since many TVs hide the setting. |
| Foreground service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | One service plays the stream while the window is up, and stops with it. |

Plain `http://` and `rtsp://` are allowed for the home network (`res/xml/network.xml`). `ConfigProvider` takes a
pushed file and only the ADB shell can use it (it requires `WRITE_SECURE_SETTINGS`). Playback is Media3
(ExoPlayer, with its HLS and RTSP modules), as in TV Hub and Frigate Glance.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, and ADB on the TV:

```bash
TV_HOST=<tv ip> ./install.sh [config.json]
```

It builds the app, installs it with `adb`, allows the corner window and opens it. Where macOS blocks the stock
`adb`, set `CLEANYOURTV` to a clone of [Clean Your TV](https://github.com/Gathin23/cleanyourtv) with its tools set up, and its `tools/tv.py` is used
instead. To build only: `./gradlew assembleRelease`; the JVM tests run with `./gradlew testReleaseUnitTest`.

There is no prebuilt APK: build it from this source.

## Limits

- **Two videos at once need two decoders.** The corner and the app below each decode a video. Many TV chips have
  two decoders; some don't, or share them badly: on Frigate Glance's test TV a second stream next to YouTube made
  YouTube stall. Prefer a camera's sub-stream, and check with what you watch.
- **Protected video** (some streaming apps' DRM) may refuse to play while another window shows video, depending on
  the chip.
- **A pointer can't click through the window.** Since Android 12 an overlay that lets touches through is drawn at
  most 80 % opaque, which would show what's underneath the video, so the corner window keeps touches. TVs have
  no touchscreen; with an air mouse, that one corner can't be clicked through.
- **A stream that fails** is retried once; then the window says it can't play and closes after a few seconds.

## Tested on

MOKA R8G (Android 14), on a home app that implements the module-card spec: a public HLS test stream from a camera entry and the same kind of stream from an M3U playlist served on the home network, playing silently in the bottom-right corner over Home; the corner window opaque (testing found Android draws a window that lets touches through at most 80 % opaque, so the window keeps touches); Stop. A second video playing underneath (YouTube) was started but not confirmed, so check with what you watch. RTSP was not tried with a real camera. The install from this repo and its `adb` path were not run; the app is the same code. The JVM tests cover the playlist reader, addresses, hiding camera passwords and the window's place.

## Origin

Written as a [Clean Your TV](https://github.com/Gathin23/cleanyourtv) add-on and kept here as its own project, listed in that repo's
[showcase](https://github.com/Gathin23/cleanyourtv/blob/main/SHOWCASE.md).

## License

[MIT](LICENSE). Maintained by [@maxhayim](https://github.com/maxhayim); issues and pull requests are welcome here.
