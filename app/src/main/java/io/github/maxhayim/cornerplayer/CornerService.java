package io.github.maxhayim.cornerplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays one stream, one of the TV's inputs (HDMI), a camera's pictures, or every camera in turn, in a small window
 * in a corner, over whatever is on screen. The window never takes focus or keys, so the remote keeps controlling
 * the app below; Corner Player's own screen or network control changes or closes it. One opened for a few seconds
 * from the network (a doorbell) gives the corner back to what was there when it ends. A doorbell popup carries a
 * banner with the doorbell's name and the time; with no camera, or one that can't be played, the banner alone.
 * Runs only while the window is up.
 */
public class CornerService extends Service {
    static final String TAG = "CornerPlayer";
    private static final String CHANNEL = "corner";
    private static final String EXTRA_REQUEST = "request", EXTRA_SECONDS = "seconds", EXTRA_RESTORE = "restore",
            EXTRA_CHIME = "chime";
    private static final String ACTION_STOP = "stop", ACTION_LAYOUT = "layout", ACTION_END_SHORT = "endShort";
    /** A stream that fails is retried once, then the window says so and closes. */
    private static final long CLOSE_AFTER_ERROR_MS = 8000;
    /** Cycling, a camera that fails is skipped after this long. */
    private static final long SKIP_AFTER_ERROR_MS = 3000;
    /** A camera that sends stills is asked for a new one this often. */
    private static final long STILL_EVERY_MS = 1000;
    /** Stills: this many failures in a row and the window says it can't play. */
    private static final int STILL_FAILURES = 3;
    /** Over a picture the banner lets a little through; alone, as a card, it hides what's under it. */
    private static final int CARD = 0xFF0D1117, ACCENT = 0xFFFF9F43;

    /** What plays now, for the app's screen and network control; null when nothing does. */
    static volatile String playing;
    /** Whether the corner is cycling through the cameras. */
    static volatile boolean cycling;
    /** The doorbell whose popup is up; null when none is. */
    static volatile String doorbell;
    /** What the window shows: video, mjpeg, snapshot, input or notification; null when it's closed. */
    static volatile String showing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable closeLater = this::stopSelf;
    private final Runnable endShort = this::endShort;
    private final Runnable nextCamera = this::nextCamera;
    private WindowManager wm;
    private FrameLayout window;
    /** Holds the picture: a TextureView for a stream, a TvView for an input, an ImageView for pictures. */
    private FrameLayout screen;
    private TextView label;
    /** A doorbell popup's banner, and its tag saying whether the picture is live or a snapshot. */
    private LinearLayout banner;
    private TextView bannerTag;
    private ExoPlayer player;
    private TvView tv;
    private ImageView picture;
    private boolean retried;
    /** What the corner shows, with its own window settings, and what it goes back to after a short one. */
    private final Corner.Slot slot = new Corner.Slot();
    private List<Corner.Source> cameras;
    private int cameraIndex;
    /** Bumped whenever the picture changes, so a pictures thread for an older one stops. */
    private volatile int generation;
    private volatile HttpURLConnection pictures;
    /** The window's width in pixels, to decode pictures no larger than they are shown. */
    private volatile int windowWidth = 640;

    static void play(Context c, String name, String url) {
        start(c, new Corner.Request(name, url, 0, null, 0, null), 0);
    }

    /** Every camera in turn, as long as the app's Cycle setting says. */
    static void cycle(Context c) {
        start(c, new Corner.Request(null, null, Config.cycle(c), null, 0, null), 0);
    }

    /** With seconds above 0, it ends by itself after that long (a doorbell, say), and what was there comes back. */
    static void start(Context c, Corner.Request r, int seconds) {
        ring(c, r, seconds, true, false);
    }

    /**
     * A short one (a doorbell popup): restore says whether the corner goes back to what was in it when it ends,
     * chime whether a short chime sounds as it opens.
     */
    static void ring(Context c, Corner.Request r, int seconds, boolean restore, boolean chime) {
        c.startForegroundService(new Intent(c, CornerService.class).putExtra(EXTRA_REQUEST, r.toJson())
                .putExtra(EXTRA_SECONDS, seconds).putExtra(EXTRA_RESTORE, restore).putExtra(EXTRA_CHIME, chime));
    }

    static void stop(Context c) {
        Config.saveLast(c, null);
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_STOP));
    }

    /** Ends a short one (a doorbell popup) now, as its timer would; anything else stays. */
    static void dismiss(Context c) {
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_END_SHORT));
    }

    /** Corner, size or sound changed in the app: the window follows at once. */
    static void relayout(Context c) {
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_LAYOUT));
    }

    static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** The TV's own inputs (HDMI and the like) by the names the TV gives them; none on a box or stick. */
    static List<Corner.Source> inputs(Context c) {
        List<Corner.Source> out = new ArrayList<>();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (!i.isPassthroughInput() || i.isHidden(c)) continue;
                CharSequence label = i.loadCustomLabel(c);
                if (label == null || label.length() == 0) label = i.loadLabel(c);
                out.add(new Corner.Source(label.toString(), Corner.inputUrl(i.getId()), false));
            }
        } catch (RuntimeException e) {
            // The TV's input service is missing or broken: no inputs.
        }
        // The TV lists them in no order: HDMI1, HDMI2, ... by name.
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action) || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_LAYOUT.equals(action)) {
            // The app's settings win over what this window asked for (a doorbell popup keeps its own).
            if (slot.now != null) slot.replace(slot.now.plain());
            layout();
            return START_NOT_STICKY;
        }
        if (ACTION_END_SHORT.equals(action)) {
            if (slot.isShort()) {
                main.removeCallbacks(endShort);
                endShort();
            } else if (window == null) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        Corner.Request r = Corner.Request.fromJson(intent.getStringExtra(EXTRA_REQUEST));
        if (r == null || !canDraw(this)) {
            if (window == null) stopSelf();
            return START_NOT_STICKY;
        }
        int seconds = Corner.seconds(String.valueOf(intent.getIntExtra(EXTRA_SECONDS, 0)));
        main.removeCallbacks(endShort);
        // A second short one keeps the first's way back.
        slot.open(r, seconds > 0, intent.getBooleanExtra(EXTRA_RESTORE, true));
        if (seconds > 0) main.postDelayed(endShort, seconds * 1000L);
        else Config.saveLast(this, r);
        if (intent.getBooleanExtra(EXTRA_CHIME, false)) chime();
        begin(slot.now);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        release();
        if (window != null) {
            try {
                wm.removeView(window);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        window = null;
        playing = null;
        cycling = false;
        doorbell = null;
        showing = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Shows r, which the slot already holds. */
    private void begin(Corner.Request r) {
        main.removeCallbacks(nextCamera);
        main.removeCallbacks(closeLater);
        doorbell = r.bell;
        if (r.card()) {
            cameras = null;
            cycling = false;
            showCard(false);
        } else if (r.cycle > 0) {
            cameras = Config.cameras(this);
            if (cameras.isEmpty()) {
                stopSelf();
                return;
            }
            cameraIndex = 0;
            cycling = true;
            showCamera();
        } else {
            cameras = null;
            cycling = false;
            show(r.name == null ? Corner.shown(r.url) : r.name, r.url);
        }
        layout();
    }

    /** The short one from the network is over: back to what was there, or close. */
    private void endShort() {
        Corner.Request b = slot.end();
        if (b != null) begin(b);
        else stopSelf();
    }

    private void showCamera() {
        Corner.Source s = cameras.get(cameraIndex % cameras.size());
        show(s.name, s.url);
        main.postDelayed(nextCamera, slot.now.cycle * 1000L);
    }

    private void nextCamera() {
        main.removeCallbacks(nextCamera);
        cameraIndex++;
        showCamera();
    }

    private void show(String name, String url) {
        main.removeCallbacks(closeLater);
        retried = false;
        boolean input = Corner.isInput(url);
        if (window == null) build(input);
        if (window == null) return;
        Corner.Request r = slot.now;
        label.animate().cancel();
        label.setText(name);
        label.setAlpha(1f);
        release();
        screen.removeAllViews();
        frame(input);
        setBanner(r != null && r.bell != null ? r : null, false);
        if (r != null && r.bell != null) label.setAlpha(0f);
        if (input) {
            setShowing("input");
            showInput(name, Corner.inputId(url));
        } else {
            setShowing(null);
            if (r == null || r.bell == null) fadeLabel();
            if (url.toLowerCase(Locale.ROOT).startsWith("http") && !Corner.surelyVideo(url)) look(name, url);
            else showStream(name, url);
        }
        playing = name;
    }

    /** A doorbell popup with nothing to play, or whose camera can't be played: its banner alone. */
    private void showCard(boolean noVideo) {
        main.removeCallbacks(closeLater);
        Corner.Request r = slot.now;
        if (window == null) build(false);
        if (window == null) return;
        release();
        screen.removeAllViews();
        frame(false);
        label.animate().cancel();
        label.setAlpha(0f);
        setBanner(null, false);
        LinearLayout card = bannerView(r, true);
        screen.addView(card, new FrameLayout.LayoutParams(-1, -2));
        setShowing("notification");
        if (noVideo) bannerTag.setText("NO VIDEO");
        playing = r.bell;
    }

    /** Puts a doorbell's banner over the picture (r not null), or takes it away. */
    private void setBanner(Corner.Request r, boolean card) {
        if (banner != null && banner.getParent() == window) window.removeView(banner);
        banner = null;
        bannerTag = null;
        if (r == null) return;
        LinearLayout b = bannerView(r, card);
        window.addView(b, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    /**
     * The doorbell's banner: a bell, its name, "Someone is at the door" (or motion), the time and whether the picture
     * is live. Sized to the window so it reads from across the room.
     */
    private LinearLayout bannerView(Corner.Request r, boolean card) {
        float dp = getResources().getDisplayMetrics().density;
        int w = rectFor(r)[2];
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(Math.max(8 * dp, w * 0.03f));
        int vpad = card ? pad * 3 / 2 : pad;
        b.setPadding(pad, vpad, pad, vpad);
        if (!card) {
            // Drawn over the bottom of the picture: a fade to dark, so the text reads on any picture and the
            // picture still fills the whole popup.
            b.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0xE6000000, 0x99000000, 0x00000000}));
            b.setPadding(pad, pad * 3, pad, pad);
        }

        int icon = Math.round(Math.max(28 * dp, w * (card ? 0.13f : 0.09f)));
        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.ic_bell);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        bell.setBackground(dot);
        int ip = icon / 5;
        bell.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(icon, icon);
        lp.rightMargin = pad;
        b.addView(bell, lp);

        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(r.bell, Math.max(16 * dp, w * 0.052f), Color.WHITE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        lines.addView(name);
        lines.addView(text(r.motion ? "Motion at the door" : "Someone is at the door",
                Math.max(13 * dp, w * 0.04f), 0xFFE6EDF3));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.addView(text(DateFormat.getTimeFormat(this).format(new Date(r.at > 0 ? r.at : System.currentTimeMillis())),
                Math.max(12 * dp, w * 0.034f), 0xFFAFB8C1));
        bannerTag = text("", Math.max(11 * dp, w * 0.03f), ACCENT);
        bannerTag.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTag.setPadding(pad, 0, 0, 0);
        meta.addView(bannerTag);
        lines.addView(meta);
        b.addView(lines, new LinearLayout.LayoutParams(0, -2, 1f));
        banner = card ? null : b;
        return b;
    }

    private TextView text(String s, float px, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        return t;
    }

    /** What the window shows now, for /status, and on a doorbell's banner: live, or a snapshot. */
    private void setShowing(String what) {
        showing = what;
        if (bannerTag == null) return;
        if ("snapshot".equals(what)) bannerTag.setText("SNAPSHOT");
        else if ("video".equals(what) || "mjpeg".equals(what) || "input".equals(what)) bannerTag.setText("LIVE");
        else bannerTag.setText("");
    }

    /** A short two-note chime (synthesised, so it needs no sound file), at the TV's notification volume. */
    private void chime() {
        new Thread(() -> {
            int rate = 22050, each = rate * 7 / 10;
            float[] notes = {659.25f, 523.25f};
            short[] pcm = new short[each * notes.length];
            for (int n = 0; n < notes.length; n++) {
                for (int i = 0; i < each; i++) {
                    double t = i / (double) rate;
                    double v = Math.sin(2 * Math.PI * notes[n] * t) * 0.8 + Math.sin(4 * Math.PI * notes[n] * t) * 0.2;
                    double attack = Math.min(1, i / (rate * 0.01));
                    pcm[n * each + i] = (short) (v * attack * Math.exp(-4 * t) * 0.45 * Short.MAX_VALUE);
                }
            }
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep(pcm.length * 1000L / rate + 200);
            } catch (Exception e) {
                Log.w(TAG, "chime: " + e.getMessage());
            } finally {
                if (track != null) track.release();
            }
        }, "chime").start();
    }

    /** Whether the window holds a doorbell popup now: its picture fills the popup, under the banner. */
    private boolean bellNow() {
        Corner.Request r = slot.now;
        return r != null && r.bell != null;
    }

    /**
     * Crops a stream to fill its view, centred, the way a doorbell's picture fills its popup. A TextureView
     * stretches what it shows to its own size, so this scales it back to the video's shape, enlarged to cover.
     */
    private void fill(TextureView v, float vw, float vh) {
        int w = v.getWidth(), h = v.getHeight();
        if (w == 0 || h == 0) {
            if (v.isAttachedToWindow()) v.post(() -> fill(v, vw, vh));
            return;
        }
        float scale = Math.max(w / vw, h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * scale / w, vh * scale / h, w / 2f, h / 2f);
        v.setTransform(m);
    }

    private void showStream(String name, String url) {
        TextureView video = new TextureView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1));
        player = new ExoPlayer.Builder(this).build();
        player.setVideoTextureView(video);
        player.setVolume(sound() ? 1f : 0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) setShowing("video");
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (bellNow() && size.width > 0 && size.height > 0)
                    fill(video, size.width * size.pixelWidthHeightRatio, size.height);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.w(TAG, "stream error: " + e.getErrorCodeName());
                if (!retried) {
                    retried = true;
                    player.prepare();
                    return;
                }
                failed(name);
            }
        });
        player.setMediaItem(MediaItem.fromUri(url));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /**
     * An http(s) address that may not be video: a camera's snapshot (a picture, asked for again every second) or
     * MJPEG (pictures one after another). Those are drawn as pictures, with no video decoder, so they play even on
     * a TV whose decoder is busy. Anything else goes to the player.
     */
    private void look(String name, String url) {
        int gen = generation;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = Net.open(url);
                pictures = c;
                Corner.Kind kind = Corner.kind(c.getContentType());
                if (kind == Corner.Kind.VIDEO) {
                    c.disconnect();
                    c = null;
                    main.post(() -> {
                        if (gen == generation) showStream(name, url);
                    });
                    return;
                }
                Log.i(TAG, "camera sends " + kind);
                main.post(() -> {
                    if (gen != generation) return;
                    picture = new ImageView(this);
                    picture.setScaleType(bellNow() ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                    screen.addView(picture, new FrameLayout.LayoutParams(-1, -1));
                    setShowing(kind == Corner.Kind.MJPEG ? "mjpeg" : "snapshot");
                });
                if (kind == Corner.Kind.MJPEG) mjpeg(gen, name, url, c);
                else stills(gen, name, url, c);
            } catch (IOException e) {
                // Not reachable this way: let the player try, and say so as it does.
                main.post(() -> {
                    if (gen == generation) showStream(name, url);
                });
            } finally {
                if (c != null) c.disconnect();
            }
        }, "pictures").start();
    }

    private void mjpeg(int gen, String name, String url, HttpURLConnection first) {
        HttpURLConnection c = first;
        byte[] buf = new byte[65536];
        for (int attempt = 0; attempt < 2 && gen == generation; attempt++) {
            try {
                if (c == null) {
                    c = Net.open(url);
                    pictures = c;
                }
                Corner.Jpegs jpegs = new Corner.Jpegs();
                try (InputStream in = c.getInputStream()) {
                    for (int n; gen == generation && (n = in.read(buf)) > 0; ) {
                        List<byte[]> done = jpegs.feed(buf, n);
                        // Only the newest: a slow TV skips pictures rather than falling behind.
                        if (!done.isEmpty()) {
                            attempt = 0;
                            showPicture(gen, done.get(done.size() - 1));
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "mjpeg: " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
                c = null;
            }
        }
        if (gen == generation) main.post(() -> {
            if (gen == generation) failed(name);
        });
    }

    private void stills(int gen, String name, String url, HttpURLConnection first) {
        HttpURLConnection c = first;
        int failures = 0;
        while (gen == generation && failures < STILL_FAILURES) {
            try {
                if (c == null) {
                    c = Net.open(url);
                    pictures = c;
                }
                byte[] b = null;
                // An expired address (Home Assistant's camera token after a while) answers 401 or 403: a failure.
                if (c.getResponseCode() / 100 == 2) {
                    try (InputStream in = c.getInputStream()) {
                        b = Net.read(in, Corner.MAX_PICTURE_BYTES);
                    }
                }
                if (b != null && showPicture(gen, b)) failures = 0;
                else failures++;
            } catch (IOException e) {
                failures++;
            } finally {
                if (c != null) c.disconnect();
                c = null;
            }
            try {
                Thread.sleep(STILL_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (gen == generation) main.post(() -> {
            if (gen == generation) failed(name);
        });
    }

    /** Decodes a picture no larger than the window shows it, and puts it up; false if it isn't one. */
    private boolean showPicture(int gen, byte[] b) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0) return false;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= windowWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length, d);
        if (bmp == null) return false;
        main.post(() -> {
            if (gen == generation && picture != null) picture.setImageBitmap(bmp);
        });
        return true;
    }

    /** An input of the TV itself (HDMI and the like), through the TV input framework, as Live TV shows it. */
    private void showInput(String name, String inputId) {
        tv = new TvView(this);
        screen.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        // The name stays up until the TV says a picture is there, so a black window tells which side it's stuck on.
        tv.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onConnectionFailed(String id) {
                Log.w(TAG, "input connection failed: " + id);
                failed(name);
            }

            @Override
            public void onDisconnected(String id) {
                Log.w(TAG, "input disconnected: " + id);
                failed(name);
            }

            @Override
            public void onVideoUnavailable(String id, int reason) {
                Log.i(TAG, "input video unavailable: " + reason);
                if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                        || reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING) return;
                if (slot.now != null && slot.now.bell != null) return;
                // Nothing plugged in, or the device on it is off: say so, and keep waiting for a picture.
                label.animate().cancel();
                label.setText("No picture on " + name);
                label.setAlpha(1f);
            }

            @Override
            public void onVideoAvailable(String id) {
                Log.i(TAG, "input video available");
                if (slot.now != null && slot.now.bell != null) return;
                label.setText(name);
                fadeLabel();
            }

            @Override
            public void onVideoSizeChanged(String id, int width, int height) {
                Log.i(TAG, "input video size: " + width + "x" + height);
            }
        });
        tv.setStreamVolume(sound() ? 1f : 0f);
        Log.i(TAG, "tuning input " + inputId);
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    private void fadeLabel() {
        label.animate().alpha(0f).setStartDelay(3000).setDuration(600).start();
    }

    /**
     * Says so; then cycling moves on, a doorbell popup keeps its banner without the picture, a short one gives the
     * corner back, and anything else closes.
     */
    private void failed(String name) {
        Corner.Request r = slot.now;
        if (r != null && r.bell != null && r.url != null) {
            // The doorbell still says someone is there; its timer runs on.
            Log.w(TAG, "doorbell camera can't be played: banner only");
            slot.replace(r.cardOnly());
            showCard(true);
            layout();
            return;
        }
        label.animate().cancel();
        label.setText("Can't play " + name);
        label.setAlpha(1f);
        if (cameras != null) {
            main.removeCallbacks(nextCamera);
            main.postDelayed(nextCamera, SKIP_AFTER_ERROR_MS);
        } else if (slot.isShort()) {
            main.removeCallbacks(endShort);
            main.postDelayed(endShort, CLOSE_AFTER_ERROR_MS);
        } else {
            main.postDelayed(closeLater, CLOSE_AFTER_ERROR_MS);
        }
    }

    private void release() {
        generation++;
        HttpURLConnection c = pictures;
        pictures = null;
        // Closing a socket is network work: not on the main thread.
        if (c != null) new Thread(c::disconnect).start();
        picture = null;
        if (player != null) player.release();
        player = null;
        if (tv != null) tv.reset();
        tv = null;
    }

    private void build(boolean input) {
        window = new FrameLayout(this);
        window.setBackgroundColor(Color.BLACK);
        float dp = getResources().getDisplayMetrics().density;
        window.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * dp);
            }
        });
        frame(input);
        screen = new FrameLayout(this);
        window.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(Color.WHITE);
        label.setBackgroundColor(0x99000000);
        int p = Math.round(8 * dp);
        label.setPadding(p, p / 2, p, p / 2);
        window.addView(label, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        try {
            wm.addView(window, params());
        } catch (RuntimeException e) {
            Log.w(TAG, "window not shown (display over other apps not allowed?): " + e.getMessage());
            window = null;
            stopSelf();
        }
    }

    /**
     * Rounded corners and a shadow for a stream. An input's picture is a hardware video layer: many TV chips can only
     * show it unclipped and without a shadow, and show black otherwise.
     */
    private void frame(boolean input) {
        window.setClipToOutline(!input);
        window.setElevation(input ? 0 : 8 * getResources().getDisplayMetrics().density);
        boolean card = slot.now != null && slot.now.card();
        window.setBackgroundColor(card ? CARD : Color.BLACK);
    }

    /** This window's sound if it asked for one, else the app's. A doorbell popup's camera is silent unless asked. */
    private boolean sound() {
        Corner.Request now = slot.now;
        if (now != null && now.sound != null) return now.sound;
        return now == null || now.bell == null ? Config.sound(this) : false;
    }

    private void layout() {
        if (player != null) player.setVolume(sound() ? 1f : 0f);
        if (tv != null) tv.setStreamVolume(sound() ? 1f : 0f);
        if (window != null) wm.updateViewLayout(window, params());
    }

    private int[] rectFor(Corner.Request now) {
        DisplayMetrics m = getResources().getDisplayMetrics();
        Corner.Place place = now != null && now.place != null ? now.place : Config.place(this);
        int size = now != null && now.size > 0 ? now.size : Config.size(this);
        return Corner.rect(m.widthPixels, m.heightPixels, place, size, Math.round(32 * m.density));
    }

    private WindowManager.LayoutParams params() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        Corner.Request now = slot.now;
        int[] r = rectFor(now);
        windowWidth = r[2];
        boolean card = now != null && now.card();
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(r[2],
                // The banner alone is only as tall as its text.
                card ? WindowManager.LayoutParams.WRAP_CONTENT : r[3],
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not FLAG_NOT_TOUCHABLE: since Android 12 a window that lets touches through is drawn at most 80 %
                // opaque, which would show what is under the video. TVs have no touchscreen; a pointer can't click
                // through this one corner, and the remote's keys never come here (not focusable).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (card) {
            // Anchored to its corner, so a banner at the bottom grows upwards.
            Corner.Place place = now.place != null ? now.place : Config.place(this);
            boolean left = place == Corner.Place.TOP_LEFT || place == Corner.Place.BOTTOM_LEFT;
            boolean top = place == Corner.Place.TOP_LEFT || place == Corner.Place.TOP_RIGHT;
            p.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT);
            p.x = p.y = Math.round(32 * m.density);
        } else {
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = r[0];
            p.y = r[1];
        }
        p.setTitle("Corner Player");
        return p;
    }
}
