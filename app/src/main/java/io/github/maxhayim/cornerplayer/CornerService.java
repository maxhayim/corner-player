package io.github.maxhayim.cornerplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.media.tv.TvContract;
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
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Plays one stream, or one of the TV's inputs (HDMI), in a small window in a corner, over whatever is on screen. The window never takes focus or
 * keys, so the remote keeps controlling the app below; Corner Player's own screen changes or closes it.
 * Runs only while the window is up.
 */
public class CornerService extends Service {
    static final String TAG = "CornerPlayer";
    private static final String CHANNEL = "corner";
    private static final String EXTRA_NAME = "name", EXTRA_URL = "url";
    private static final String ACTION_STOP = "stop", ACTION_LAYOUT = "layout";
    /** A stream that fails is retried once, then the window says so and closes. */
    private static final long CLOSE_AFTER_ERROR_MS = 8000;

    /** What plays now, for the app's screen; null when nothing does. */
    static volatile String playing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FrameLayout window;
    /** Holds the picture: a TextureView for a stream, a TvView for an input. */
    private FrameLayout screen;
    private TextView label;
    private ExoPlayer player;
    private TvView tv;
    private boolean retried;

    static void play(Context c, String name, String url) {
        c.startForegroundService(new Intent(c, CornerService.class).putExtra(EXTRA_NAME, name).putExtra(EXTRA_URL, url));
    }

    static void stop(Context c) {
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_STOP));
    }

    /** Corner, size or sound changed: the window follows at once. */
    static void relayout(Context c) {
        if (playing != null) c.startService(new Intent(c, CornerService.class).setAction(ACTION_LAYOUT));
    }

    static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
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
            layout();
            return START_NOT_STICKY;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        String name = intent.getStringExtra(EXTRA_NAME);
        if (!(Corner.validUrl(url) || Corner.isInput(url)) || !canDraw(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        show(name == null ? Corner.shown(url) : name, url);
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void show(String name, String url) {
        main.removeCallbacksAndMessages(null);
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
            showStream(name, url);
        }
        playing = name;
    }

    private void showStream(String name, String url) {
        TextureView video = new TextureView(this);
        screen.addView(video, new FrameLayout.LayoutParams(-1, -1));
        player = new ExoPlayer.Builder(this).build();
        player.setVideoTextureView(video);
        player.setVolume(Config.sound(this) ? 1f : 0f);
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
        tv.setStreamVolume(Config.sound(this) ? 1f : 0f);
        Log.i(TAG, "tuning input " + inputId);
        tv.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
    }

    private void fadeLabel() {
        label.animate().alpha(0f).setStartDelay(3000).setDuration(600).start();
    }

    private void failed(String name) {
        label.animate().cancel();
        label.setText("Can't play " + name);
        label.setAlpha(1f);
        main.postDelayed(CornerService.this::stopSelf, CLOSE_AFTER_ERROR_MS);
    }

    private void release() {
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

    private void layout() {
        if (player != null) player.setVolume(Config.sound(this) ? 1f : 0f);
        if (tv != null) tv.setStreamVolume(Config.sound(this) ? 1f : 0f);
        if (window != null) wm.updateViewLayout(window, params());
    }

    private WindowManager.LayoutParams params() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int[] r = Corner.rect(m.widthPixels, m.heightPixels, Config.place(this), Config.size(this),
                Math.round(32 * m.density));
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
