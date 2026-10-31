<p align="center">
  <img src="assets/logo.png" alt="Corner Player Logo" width="200"/>
</p>

<p align="center">
  <strong>Picture in picture for Android TV: your own camera, stream or HDMI input, in a corner, over any app</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/tv">
    <img src="https://img.shields.io/badge/Android%20TV-8%2B-3DDC84" alt="Android TV 8+">
  </a>
  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-green" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Sources-RTSP%20%7C%20HLS%20%7C%20M3U%20%7C%20HDMI-lightgrey" alt="Sources: RTSP, HLS, M3U, HDMI">
  <img src="https://img.shields.io/badge/Channels%20included-none-FF9F43" alt="No channels included">
</p>

<h1>Corner Player</h1>

<p>Corner Player is an open-source picture-in-picture app for Android TV. Your own camera (a baby monitor, the front door), a stream from your own M3U playlist or one of the TV's HDMI inputs plays in a small window in a corner, over whatever you're watching: any app, Live TV or an HDMI input. The window never takes the remote's keys, so the app below keeps working as before, and it is silent unless you turn its sound on.</p>

<p>It is for your own cameras and streams: it comes with no channels and points to none, and it only plays the addresses you give it.</p>

<h2>Screenshots</h2>

<p align="center">
  <img src="screenshot.jpg" alt="Corner Player on a TV, playing a test stream over Home" width="720"/>
</p>

<p align="center"><sub>The video in the corner is a public HLS test stream of <a href="https://peach.blender.org/">Big Buck Bunny</a> (© Blender Foundation, <a href="https://creativecommons.org/licenses/by/3.0/">CC BY 3.0</a>). App banners belong to their owners.</sub></p>

<h2>Features</h2>

<ul>
  <li><strong>Over anything</strong>: Android TV's own picture in picture only works for apps that support it; Corner Player plays its own sources, so it works over any app, Live TV or an HDMI input</li>
  <li><strong>Your cameras</strong>: <code>rtsp://</code> and <code>http(s)://</code> cameras by name; add them on the TV or push them from a computer, so passwords are never typed on the TV</li>
  <li><strong>Your playlist</strong>: the channels of an M3U playlist you point it at</li>
  <li><strong>HDMI inputs</strong>: on a TV with HDMI inputs, any of them (a game console, a set-top box, a camera's HDMI out) by the name the TV gives it</li>
  <li><strong>Network control</strong>: Home Assistant, a doorbell or any device on your network opens a camera or input in the corner with an HTTP request, for as long as you like, and closes it; off unless you switch it on, and every request needs the TV's token</li>
  <li><strong>Snapshot and MJPEG cameras</strong>: a camera address that answers with a picture is refreshed every second, and an MJPEG stream is shown picture by picture; neither needs a video decoder, so they play even where a second video can't</li>
  <li><strong>Cycle cameras</strong>: every camera in turn, 5 to 60 seconds each, like a security monitor</li>
  <li><strong>Reopen after restart</strong>: optionally, what was in the corner comes back when the TV starts</li>
  <li><strong>Any corner, three sizes</strong>: bottom right (default), bottom left, top left, top right; 25, 33 (default) or 40 % of the screen's width, 16:9</li>
  <li><strong>Hands off the remote</strong>: the window never takes focus or keys; the app below keeps working</li>
  <li><strong>Silent by default</strong>: sound off unless you switch it on</li>
  <li><strong>Passwords stay hidden</strong>: on screen, addresses show only the scheme, host and port</li>
  <li><strong>Private</strong>: connects only to the addresses you give it, and listens only when you switch network control on; no telemetry, no update checks, no ads</li>
  <li>Playback by <a href="https://developer.android.com/media/media3">Media3 (ExoPlayer)</a>, with its HLS and RTSP modules</li>
  <li>A <a href="https://github.com/Gathin23/cleanyourtv/blob/main/docs/modules.md">module card</a> for the <a href="https://github.com/Gathin23/cleanyourtv">Clean Your TV</a> launcher ("Corner Player", orange glow); on any other home it's an ordinary app</li>
</ul>

<h2>Requirements</h2>

<ul>
  <li>An Android TV or Google TV device on <strong>Android 8 or newer</strong></li>
  <li>Your own camera with an RTSP or HTTP stream, or your own M3U playlist, on a network the TV can reach; or, on a TV, something plugged into one of its HDMI inputs</li>
  <li>To install: a computer with <a href="https://developer.android.com/tools/releases/platform-tools">ADB</a> and Developer options → Network debugging on the TV (the installer also allows the corner window, which many TVs hide)</li>
</ul>

<h2>Installation and Setup</h2>

<h3>From a release (recommended)</h3>

<ol>
  <li>Download <code>CornerPlayer-x.y.z.apk</code> from the <a href="../../releases/latest">latest release</a>.</li>
  <li>Install it, allow its corner window and open it:
<pre>
TV_HOST=&lt;tv ip&gt; APK=CornerPlayer-0.1.0.apk ./install.sh [config.json]
</pre>
  </li>
</ol>

<h3>From source</h3>

<p>JDK 17 and Android SDK platform 34 with build-tools 34.0.0:</p>

<pre>
TV_HOST=&lt;tv ip&gt; ./install.sh [config.json]
</pre>

<p>It builds the app, installs it with <code>adb</code>, allows the corner window and opens it. Where macOS blocks the stock <code>adb</code>, set <code>CLEANYOURTV</code> to a clone of <a href="https://github.com/Gathin23/cleanyourtv">Clean Your TV</a> with its tools set up, and its <code>tools/tv.py</code> is used instead. To build only: <code>./gradlew assembleRelease</code>; the JVM tests run with <code>./gradlew testReleaseUnitTest</code>.</p>

<h3>Adding cameras</h3>

<p>Cameras usually publish RTSP: look for "RTSP" in the camera's app or manual. A sub-stream (smaller picture) is the better choice for a corner, for example <code>rtsp://user:password@192.168.1.20:554/stream2</code>. Type the address on the TV, or push cameras and the playlist from a computer as a file, so camera passwords are never typed on the TV or put on a command line:</p>

<pre>
cat > backup/corner-player.json &lt;&lt;'JSON'
{"cameras": [{"name": "Nursery", "url": "rtsp://user:password@192.168.1.20:554/stream2"}],
 "playlist": "http://192.168.1.5:8080/playlist.m3u"}
JSON
TV_HOST=&lt;tv ip&gt; ./install.sh backup/corner-player.json
</pre>

<p>The app takes the file into its own storage and deletes it. Keep the file out of git (<code>backup/</code> is git-ignored).</p>

<h2>Usage</h2>

<p>Open Corner Player (or its card) and press OK on a camera, channel or input: it starts in the corner and you're back to what you were watching. Open it again to switch, or press <strong>Stop</strong>.</p>

<table>
  <tr><th>Row</th><th>What it does</th></tr>
  <tr><td><strong>Stop</strong></td><td>Closes the corner window (shown while one plays, focused first).</td></tr>
  <tr><td><strong>Cameras</strong></td><td>Your cameras by name; OK plays one. <strong>Add a camera</strong> asks for a name and an <code>rtsp://</code> or <code>http(s)://</code> address; <strong>Remove cameras</strong> (OK twice) removes them all. With two or more, <strong>Cycle cameras</strong> shows each in turn; ◀ ▶ sets 5 to 60 seconds each. A camera address can also be its snapshot (a picture) or MJPEG address.</td></tr>
  <tr><td><strong>Channels</strong></td><td>The channels of your playlist; OK plays one. <strong>Playlist address</strong> is an M3U playlist.</td></tr>
  <tr><td><strong>Inputs</strong></td><td>The TV's HDMI (and other) inputs, by the names the TV shows; OK plays one. Boxes and sticks have none.</td></tr>
  <tr><td><strong>Network control</strong></td><td>Off (default) or On. On, it shows this TV's address and the token your automations send; <strong>New token</strong> (OK twice) replaces it.</td></tr>
  <tr><td><strong>Corner</strong></td><td>Bottom right (default), bottom left, top left, top right.</td></tr>
  <tr><td><strong>Size</strong></td><td>25, 33 (default) or 40 % of the screen's width, 16:9.</td></tr>
  <tr><td><strong>Sound</strong></td><td>Off (default) or On.</td></tr>
  <tr><td><strong>Reopen after restart</strong></td><td>Off (default) or On: what was in the corner (not a short one from the network) comes back when the TV starts. Stop forgets it.</td></tr>
</table>

<h3>Network control</h3>

<p>Switch on <strong>Network control</strong> and Corner Player listens on port 8765 for requests from your home network: a doorbell press shows the front door, Home Assistant shows the nursery when it hears crying. The automation stays in your home server; the TV only opens and closes the window. Every request carries the token shown on the TV (or pushed as <code>"token"</code> in <code>config.json</code>, with <code>"remote": true</code>).</p>

<table>
  <tr><th>Request</th><th>What it does</th></tr>
  <tr><td><code>POST /play</code> with <code>name=</code></td><td>Opens a camera, input or playlist channel by its name (any case), for example <code>name=Front door</code> or <code>name=HDMI2</code>.</td></tr>
  <tr><td><code>POST /play</code> with <code>url=</code></td><td>Opens any <code>rtsp://</code> or <code>http(s)://</code> stream; <code>title=</code> is the name it shows.</td></tr>
  <tr><td><code>POST /play</code> with <code>cycle=</code></td><td>Shows every camera in turn, that many seconds each.</td></tr>
  <tr><td><code>seconds=</code></td><td>With any of them: it ends by itself after that long (up to 3600), and the corner goes back to what was in it before, or closes if it was empty. A doorbell over the baby monitor, then the baby monitor again. Without it, it stays up until stopped.</td></tr>
  <tr><td><code>corner=</code>, <code>size=</code>, <code>sound=</code></td><td>With any of them: this window only, for example <code>corner=top-left</code>, <code>size=50</code> (15 to 60 % of the screen's width), <code>sound=on</code>. Without them, the app's settings.</td></tr>
  <tr><td><code>POST /stop</code></td><td>Closes the window.</td></tr>
  <tr><td><code>GET /status</code></td><td><code>{"playing": "Front door", "cycling": false}</code>; <code>playing</code> is <code>null</code> when the corner is closed.</td></tr>
</table>

<p>Parameters go in the query, a form body or a JSON body. Replies are JSON: <code>{"ok": true, "playing": ...}</code> or <code>{"error": ...}</code> with status 400, 401 (no or wrong token), 404 (no such camera or input) or 409 (the window isn't allowed).</p>

<pre>
curl -X POST -H "Authorization: Bearer &lt;token&gt;" \
  -d "name=Front door" -d seconds=60 http://&lt;tv ip&gt;:8765/play
</pre>

<p>In Home Assistant, a <code>rest_command</code> (keep the token in <code>secrets.yaml</code>):</p>

<pre>
rest_command:
  tv_corner_play:
    url: "http://&lt;tv ip&gt;:8765/play"
    method: post
    headers:
      Authorization: !secret corner_player_auth   # "Bearer &lt;token&gt;"
    content_type: "application/json"
    payload: '{"name": "{{ name }}", "seconds": {{ seconds | default(60) }}}'
  tv_corner_stop:
    url: "http://&lt;tv ip&gt;:8765/stop"
    method: post
    headers:
      Authorization: !secret corner_player_auth
</pre>

<p>Then an automation calls <code>rest_command.tv_corner_play</code> with <code>name: Front door</code> when the doorbell rings. Give the TV a fixed address in your router so automations keep finding it.</p>

<h2>How It Works</h2>

<p>Corner Player draws a small, always-on-top window (Display over other apps) that never takes focus, so key presses keep going to the app underneath. A foreground service plays the stream into it with Media3 while the window is up, and stops with it. An HDMI input plays through Android's TV input framework (a <code>TvView</code>), the way the TV's own Live TV or Inputs app shows it; it needs no extra permission.</p>

<table>
  <tr><th>Permission</th><th>Why</th></tr>
  <tr><td><code>INTERNET</code></td><td>The cameras and playlist you add, and nothing else.</td></tr>
  <tr><td>Display over other apps (<code>SYSTEM_ALERT_WINDOW</code>)</td><td>To draw the corner window over other apps. It never takes focus or the remote's keys. Granted by <code>install.sh</code>, since many TVs hide the setting.</td></tr>
  <tr><td>Foreground service (<code>FOREGROUND_SERVICE</code>, <code>FOREGROUND_SERVICE_MEDIA_PLAYBACK</code>)</td><td>One service plays the stream while the window is up, and stops with it.</td></tr>
  <tr><td><code>FOREGROUND_SERVICE_SPECIAL_USE</code>, <code>RECEIVE_BOOT_COMPLETED</code>, <code>ACCESS_NETWORK_STATE</code></td><td>Network control only, while it's on: a second service listens for your automations and comes back after a restart; the TV's address is shown on screen.</td></tr>
</table>

<p>Plain <code>http://</code> and <code>rtsp://</code> are allowed for the home network (<code>res/xml/network.xml</code>). <code>ConfigProvider</code> takes a pushed file and only the ADB shell can use it (it requires <code>WRITE_SECURE_SETTINGS</code>). More in <a href="SECURITY.md">SECURITY.md</a>.</p>

<h2>Known Limitations</h2>

<ul>
  <li><strong>Two videos at once need two decoders.</strong> The corner and the app below each decode a video. Many TV chips have two decoders; some don't, or share them badly: on one test TV a second stream next to YouTube made YouTube stall. Prefer a camera's sub-stream, or its snapshot or MJPEG address, which needs no decoder, and check with what you watch.</li>
  <li><strong>Protected video</strong> (some streaming apps' DRM) may refuse to play while another window shows video, depending on the chip.</li>
  <li><strong>A pointer can't click through the window.</strong> Since Android 12 an overlay that lets touches through is drawn at most 80 % opaque, which would show what's underneath the video, so the corner window keeps touches. TVs have no touchscreen; with an air mouse, that one corner can't be clicked through.</li>
  <li><strong>HDMI is up to the TV.</strong> Corner Player shows the inputs the TV lists for apps; some makers list none, or refuse a second app. Most TVs can show only one HDMI input at a time, so an HDMI input in the corner over another HDMI input (or over the TV's own tuner) usually won't work; over an app it should. With nothing on the input, the window says "No picture" and waits. An input's window has square corners and no shadow: many TV chips show a black window otherwise. Its name stays on it until the TV reports a picture, so on a black window, a name that stays means the TV sends no picture, and a name that fades means the TV says it does (please <a href="../../issues">open an issue</a> with the TV's model). On some chips (Realtek) a screenshot shows the HDMI picture's place wrongly; the TV itself shows it in the corner.</li>
  <li><strong>Network control is plain HTTP</strong> on your home network: the token keeps others on the network out, but anyone who can read your network traffic can read it too. Never forward port 8765 to the internet; from away, use a VPN to your home network.</li>
  <li><strong>Snapshot cameras</strong> are asked for a new picture every second, and a user name and password in their address are sent as Basic authentication; cameras that only take Digest authentication for snapshots won't send one.</li>
  <li><strong>A stream that fails</strong> is retried once; then the window says it can't play and closes after a few seconds.</li>
</ul>

<h2>Tested On</h2>

<p>MOKA R8G (Android 14), on a home app that implements the module-card spec: a public HLS test stream from a camera entry and the same kind of stream from an M3U playlist served on the home network, playing silently in the bottom-right corner over Home; the corner window opaque; Stop. A second video playing underneath (YouTube) was started but not confirmed, so check with what you watch. RTSP was not tried with a real camera. The signed v0.1.0 release APK installed with <code>install.sh</code> (<code>APK=</code>), corner window allowed. HDMI inputs (added for <a href="../../issues/1">#1</a>, v0.2.0): the same TV lists AV and HDMI1 to HDMI3; HDMI1 and HDMI2 opened in the bottom-right corner over Home with nothing plugged in, saying "No picture on HDMI1", and Stop let go of the input. A picture from a plugged-in device was not tried yet. v0.4.0's snapshot and MJPEG cameras, cycling, per-window corner, size and sound, the corner going back after a short one, channels by name over the network and Reopen after restart build and pass the tests, but were not tried on a TV yet. Network control (added for <a href="../../issues/3">#3</a>, v0.3.0), on the same TV with Corner Player in the background over Home: requests without or with a wrong token refused (401), unknown names, bad addresses, paths and methods answered with their errors; <code>POST /play</code> opened HDMI1 (JSON body, <code>seconds=8</code>, closed by itself) and HDMI2 (form body), <code>/status</code> followed, <code>/stop</code> closed it; it was listening again after an in-place update, and switching it off closed the port. Restarting the TV was not tried. The JVM tests cover the playlist reader, addresses and inputs, hiding camera passwords, the window's place, and network control's requests and token.</p>

<h2>Origin</h2>

<p>Written as a <a href="https://github.com/Gathin23/cleanyourtv">Clean Your TV</a> add-on and kept here as its own project, listed in that repo's <a href="https://github.com/Gathin23/cleanyourtv/blob/main/SHOWCASE.md">showcase</a>.</p>

<h2>License</h2>

This project is licensed under the MIT License.

See the [LICENSE](LICENSE) file for details.
Full license text: https://opensource.org/licenses/MIT

<h2>Contributing</h2>

<p>Pull requests are welcome. Open an issue first to discuss ideas or report bugs. See <a href="CONTRIBUTING.md">CONTRIBUTING.md</a> and the <a href="CODE_OF_CONDUCT.md">Code of Conduct</a>.</p>

<h2>Related Projects</h2>

<ul>
  <li><a href="https://github.com/Gathin23/cleanyourtv">Clean Your TV</a>: debloat tools and a lean launcher for Android TV, whose module cards Corner Player supports</li>
  <li><a href="https://github.com/maxhayim/webmote">Webmote</a>: a remote for your Android TV in your phone's or computer's browser</li>
  <li><a href="https://github.com/androidx/media">AndroidX Media3 (ExoPlayer)</a>: the player inside</li>
</ul>

<h2>Acknowledgments</h2>

<p>Playback by <a href="https://github.com/androidx/media">AndroidX Media3</a> (Apache License 2.0). The screenshot's test video is <a href="https://peach.blender.org/">Big Buck Bunny</a>, © Blender Foundation, <a href="https://creativecommons.org/licenses/by/3.0/">CC BY 3.0</a>.<br>
Android™, Android TV™ and Google TV™ are trademarks of Google LLC, used here solely to describe compatibility.
All other trademarks are the property of their respective owners.</p>
