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
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays one stream, one of the TV's inputs (HDMI), a camera's pictures, or every camera in turn, in a small window
 * in a corner, over whatever is on screen. The window never takes focus or keys, so the remote keeps controlling
 * the app below; Corner Player's own screen or network control changes or closes it. One opened for a few seconds
 * from the network (a doorbell) gives the corner back to what was there when it ends. Runs only while the window
 * is up.
 */
public class CornerService extends Service {
    static final String TAG = "CornerPlayer";
    private static final String CHANNEL = "corner";
    private static final String EXTRA_REQUEST = "request", EXTRA_SECONDS = "seconds";
    private static final String ACTION_STOP = "stop", ACTION_LAYOUT = "layout";
    /** A stream that fails is retried once, then the window says so and closes. */
    private static final long CLOSE_AFTER_ERROR_MS = 8000;
    /** Cycling, a camera that fails is skipped after this long. */
    private static final long SKIP_AFTER_ERROR_MS = 3000;
    /** A camera that sends stills is asked for a new one this often. */
    private static final long STILL_EVERY_MS = 1000;
    /** Stills: this many failures in a row and the window says it can't play. */
    private static final int STILL_FAILURES = 3;

    /** What plays now, for the app's screen and network control; null when nothing does. */
    static volatile String playing;
    /** Whether the corner is cycling through the cameras. */
    static volatile boolean cycling;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable closeLater = this::stopSelf;
    private final Runnable endShort = this::endShort;
    private final Runnable nextCamera = this::nextCamera;
    private WindowManager wm;
    private FrameLayout window;
    /** Holds the picture: a TextureView for a stream, a TvView for an input, an ImageView for pictures. */
    private FrameLayout screen;
    private TextView label;
    private ExoPlayer player;
    private TvView tv;
    private ImageView picture;
    private boolean retried;
    /** What the corner shows, with its own window settings. */
    private Corner.Request now;
    /** While a short one from the network is up: what to go back to when it ends. */
    private Corner.Request back;
    private boolean isShort;
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
        c.startForegroundService(new Intent(c, CornerService.class).putExtra(EXTRA_REQUEST, r.toJson())
                .putExtra(EXTRA_SECONDS, seconds));
    }

    static void stop(Context c) {
        Config.saveLast(c, null);
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_STOP));
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
            // The app's settings win over what this window asked for.
            if (now != null) now = now.plain();
            layout();
            return START_NOT_STICKY;
        }
        Corner.Request r = Corner.Request.fromJson(intent.getStringExtra(EXTRA_REQUEST));
        if (r == null || !canDraw(this)) {
            if (window == null) stopSelf();
            return START_NOT_STICKY;
        }
        int seconds = Corner.seconds(String.valueOf(intent.getIntExtra(EXTRA_SECONDS, 0)));
        main.removeCallbacks(endShort);
        if (seconds > 0) {
            // A second short one keeps the first's way back.
            if (!isShort) back = now;
            isShort = true;
            main.postDelayed(endShort, seconds * 1000L);
        } else {
            back = null;
            isShort = false;
            Config.saveLast(this, r);
        }
        begin(r);
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void begin(Corner.Request r) {
        now = r;
        main.removeCallbacks(nextCamera);
        main.removeCallbacks(closeLater);
        if (r.cycle > 0) {
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
        Corner.Request b = back;
        back = null;
        isShort = false;
        if (b != null) begin(b);
        else stopSelf();
    }

    private void showCamera() {
        Corner.Source s = cameras.get(cameraIndex % cameras.size());
        show(s.name, s.url);
        main.postDelayed(nextCamera, now.cycle * 1000L);
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
        label.animate().cancel();
        label.setText(name);
        label.setAlpha(1f);
        release();
        screen.removeAllViews();
        frame(input);
        if (input) {
            showInput(name, Corner.inputId(url));
        } else {
            fadeLabel();
            if (url.toLowerCase(Locale.ROOT).startsWith("http") && !Corner.surelyVideo(url)) look(name, url);
            else showStream(name, url);
        }
        playing = name;
    }

    private void showStream(String name, String url) {
        TextureView video = new TextureView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1));
        player = new ExoPlayer.Builder(this).build();
        player.setVideoTextureView(video);
        player.setVolume(sound() ? 1f : 0f);
        player.addListener(new Player.Listener() {
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
                    picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    screen.addView(picture, new FrameLayout.LayoutParams(-1, -1));
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
                Log.w(TAG, "mjpeg: " + e.getMessage());
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
                // Nothing plugged in, or the device on it is off: say so, and keep waiting for a picture.
                label.animate().cancel();
                label.setText("No picture on " + name);
                label.setAlpha(1f);
            }

            @Override
            public void onVideoAvailable(String id) {
                Log.i(TAG, "input video available");
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

    /** Says so; then cycling moves on, a short one gives the corner back, and anything else closes. */
    private void failed(String name) {
        label.animate().cancel();
        label.setText("Can't play " + name);
        label.setAlpha(1f);
        if (cameras != null) {
            main.removeCallbacks(nextCamera);
            main.postDelayed(nextCamera, SKIP_AFTER_ERROR_MS);
        } else if (isShort) {
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
    }

    /** This window's sound if it asked for one, else the app's. */
    private boolean sound() {
        return now != null && now.sound != null ? now.sound : Config.sound(this);
    }

    private void layout() {
        if (player != null) player.setVolume(sound() ? 1f : 0f);
        if (tv != null) tv.setStreamVolume(sound() ? 1f : 0f);
        if (window != null) wm.updateViewLayout(window, params());
    }

    private WindowManager.LayoutParams params() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        Corner.Place place = now != null && now.place != null ? now.place : Config.place(this);
        int size = now != null && now.size > 0 ? now.size : Config.size(this);
        int[] r = Corner.rect(m.widthPixels, m.heightPixels, place, size, Math.round(32 * m.density));
        windowWidth = r[2];
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(r[2], r[3],
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not FLAG_NOT_TOUCHABLE: since Android 12 a window that lets touches through is drawn at most 80 %
                // opaque, which would show what is under the video. TVs have no touchscreen; a pointer can't click
                // through this one corner, and the remote's keys never come here (not focusable).
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = r[0];
        p.y = r[1];
        p.setTitle("Corner Player");
        return p;
    }
}
