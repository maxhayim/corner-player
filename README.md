<p align="center">
  <img src="assets/logo.png" alt="Corner Player Logo" width="200"/>
</p>

<p align="center">
  <strong>Picture in picture for Android TV: your own camera or stream, in a corner, over any app</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/tv">
    <img src="https://img.shields.io/badge/Android%20TV-8%2B-3DDC84" alt="Android TV 8+">
  </a>
  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-green" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Sources-RTSP%20%7C%20HLS%20%7C%20M3U-lightgrey" alt="Sources: RTSP, HLS, M3U">
  <img src="https://img.shields.io/badge/Channels%20included-none-FF9F43" alt="No channels included">
</p>

<h1>Corner Player</h1>

<p>Corner Player is an open-source picture-in-picture app for Android TV. Your own camera (a baby monitor, the front door) or a stream from your own M3U playlist plays in a small window in a corner, over whatever you're watching: any app, Live TV or an HDMI input. The window never takes the remote's keys, so the app below keeps working as before, and it is silent unless you turn its sound on.</p>

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
  <li><strong>Any corner, three sizes</strong>: bottom right (default), bottom left, top left, top right; 25, 33 (default) or 40 % of the screen's width, 16:9</li>
  <li><strong>Hands off the remote</strong>: the window never takes focus or keys; the app below keeps working</li>
  <li><strong>Silent by default</strong>: sound off unless you switch it on</li>
  <li><strong>Passwords stay hidden</strong>: on screen, addresses show only the scheme, host and port</li>
  <li><strong>Private</strong>: connects only to the addresses you give it; no telemetry, no update checks, no ads</li>
  <li>Playback by <a href="https://developer.android.com/media/media3">Media3 (ExoPlayer)</a>, with its HLS and RTSP modules</li>
  <li>A <a href="https://github.com/Gathin23/cleanyourtv/blob/main/docs/modules.md">module card</a> for the <a href="https://github.com/Gathin23/cleanyourtv">Clean Your TV</a> launcher ("Corner Player", orange glow); on any other home it's an ordinary app</li>
</ul>

<h2>Requirements</h2>

<ul>
  <li>An Android TV or Google TV device on <strong>Android 8 or newer</strong></li>
  <li>Your own camera with an RTSP or HTTP stream, or your own M3U playlist, on a network the TV can reach</li>
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

<p>Open Corner Player (or its card) and press OK on a camera or channel: it starts in the corner and you're back to what you were watching. Open it again to switch, or press <strong>Stop</strong>.</p>

<table>
  <tr><th>Row</th><th>What it does</th></tr>
  <tr><td><strong>Stop</strong></td><td>Closes the corner window (shown while one plays, focused first).</td></tr>
  <tr><td><strong>Cameras</strong></td><td>Your cameras by name; OK plays one. <strong>Add a camera</strong> asks for a name and an <code>rtsp://</code> or <code>http(s)://</code> address; <strong>Remove cameras</strong> (OK twice) removes them all.</td></tr>
  <tr><td><strong>Channels</strong></td><td>The channels of your playlist; OK plays one. <strong>Playlist address</strong> is an M3U playlist.</td></tr>
  <tr><td><strong>Corner</strong></td><td>Bottom right (default), bottom left, top left, top right.</td></tr>
  <tr><td><strong>Size</strong></td><td>25, 33 (default) or 40 % of the screen's width, 16:9.</td></tr>
  <tr><td><strong>Sound</strong></td><td>Off (default) or On.</td></tr>
</table>

<h2>How It Works</h2>

<p>Corner Player draws a small, always-on-top window (Display over other apps) that never takes focus, so key presses keep going to the app underneath. A foreground service plays the stream into it with Media3 while the window is up, and stops with it.</p>

<table>
  <tr><th>Permission</th><th>Why</th></tr>
  <tr><td><code>INTERNET</code></td><td>The cameras and playlist you add, and nothing else.</td></tr>
  <tr><td>Display over other apps (<code>SYSTEM_ALERT_WINDOW</code>)</td><td>To draw the corner window over other apps. It never takes focus or the remote's keys. Granted by <code>install.sh</code>, since many TVs hide the setting.</td></tr>
  <tr><td>Foreground service (<code>FOREGROUND_SERVICE</code>, <code>FOREGROUND_SERVICE_MEDIA_PLAYBACK</code>)</td><td>One service plays the stream while the window is up, and stops with it.</td></tr>
</table>

<p>Plain <code>http://</code> and <code>rtsp://</code> are allowed for the home network (<code>res/xml/network.xml</code>). <code>ConfigProvider</code> takes a pushed file and only the ADB shell can use it (it requires <code>WRITE_SECURE_SETTINGS</code>). More in <a href="SECURITY.md">SECURITY.md</a>.</p>

<h2>Known Limitations</h2>

<ul>
  <li><strong>Two videos at once need two decoders.</strong> The corner and the app below each decode a video. Many TV chips have two decoders; some don't, or share them badly: on one test TV a second stream next to YouTube made YouTube stall. Prefer a camera's sub-stream, and check with what you watch.</li>
  <li><strong>Protected video</strong> (some streaming apps' DRM) may refuse to play while another window shows video, depending on the chip.</li>
  <li><strong>A pointer can't click through the window.</strong> Since Android 12 an overlay that lets touches through is drawn at most 80 % opaque, which would show what's underneath the video, so the corner window keeps touches. TVs have no touchscreen; with an air mouse, that one corner can't be clicked through.</li>
  <li><strong>A stream that fails</strong> is retried once; then the window says it can't play and closes after a few seconds.</li>
</ul>

<h2>Tested On</h2>

<p>MOKA R8G (Android 14), on a home app that implements the module-card spec: a public HLS test stream from a camera entry and the same kind of stream from an M3U playlist served on the home network, playing silently in the bottom-right corner over Home; the corner window opaque; Stop. A second video playing underneath (YouTube) was started but not confirmed, so check with what you watch. RTSP was not tried with a real camera. The signed v0.1.0 release APK installed with <code>install.sh</code> (<code>APK=</code>), corner window allowed. The JVM tests cover the playlist reader, addresses, hiding camera passwords and the window's place.</p>

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
