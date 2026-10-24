package io.github.maxhayim.cornerplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tile and module card open this: your cameras and your playlist's channels; OK plays one in the corner and
 * leaves this screen, so you're back to what you were watching with the window over it. Open the card again to
 * switch or stop. The window's corner, size and sound are set here too.
 */
public class MainActivity extends Activity {
    private static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;
    private List<Corner.Source> channels = new ArrayList<>();
    private String channelsFrom = "";
    private String playlistProblem;
    /** OK pressed once on Remove cameras: a second press removes them. */
    private boolean removeArmed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0D1117);
        page.setPadding(px(48), px(32), px(48), px(24));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        title.setTextColor(0xFFFFFFFF);
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFF8B949E);
        status.setPadding(0, 0, 0, px(16));
        page.addView(title);
        page.addView(status);
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Config.importPushed(this);
        build();
        loadPlaylist();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }

    private void loadPlaylist() {
        String url = Config.playlist(this);
        if (url.isEmpty() || url.equals(channelsFrom)) return;
        net.execute(() -> {
            List<Corner.Source> found = null;
            String why = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                int code = c.getResponseCode();
                if (code / 100 != 2) {
                    why = "The playlist address answered with error " + code + ".";
                } else {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0 && out.size() < MAX_PLAYLIST_BYTES; ) out.write(buf, 0, n);
                        found = Corner.parseM3u(out.toString(StandardCharsets.UTF_8.name()));
                        if (found.isEmpty()) why = "No channels in that playlist.";
                    }
                }
            } catch (Exception e) {
                why = "Can't read the playlist at that address.";
            } finally {
                if (c != null) c.disconnect();
            }
            List<Corner.Source> f = found;
            String w = why;
            main.post(() -> {
                if (isFinishing()) return;
                channelsFrom = url;
                channels = f == null ? new ArrayList<>() : f;
                playlistProblem = w;
                build();
            });
        });
    }

    /** Rebuilds the list; focus stays on the row it was on. */
    private void build() {
        int focused = rows.indexOfChild(rows.getFocusedChild());
        rows.removeAllViews();
        String now = CornerService.playing;
        status.setText(now != null ? "In the corner: " + now + "." : "Nothing in the corner. OK on a camera or channel plays it.");
        Row first = null;
        if (now != null) {
            first = add(new Row(this, "Stop", "Closes the corner window.", false, () -> {
                CornerService.stop(this);
                main.postDelayed(this::build, 300);
            }, null));
        }
        if (!CornerService.canDraw(this)) {
            Row r = add(new Row(this, "Allow display over other apps", "Needed for the corner window. OK opens the setting.",
                    false, this::askOverlay, null));
            r.setValue("Not allowed");
            if (first == null) first = r;
        }

        heading("Cameras");
        List<Corner.Source> cams = Config.cameras(this);
        for (Corner.Source s : cams) {
            Row r = add(new Row(this, s.name, Corner.shown(s.url), false, () -> play(s), null));
            if (s.name.equals(now)) r.setValue("Playing");
            if (first == null) first = r;
        }
        add(new Row(this, "Add a camera", "Its name, then its RTSP or HTTP address, for example"
                + " rtsp://user:password@192.168.1.20:554/stream2", false, this::addCamera, null));
        if (!cams.isEmpty()) {
            Row rm = add(new Row(this, "Remove cameras", null, false, () -> {
                if (!removeArmed) {
                    removeArmed = true;
                    build();
                    main.postDelayed(() -> {
                        removeArmed = false;
                        build();
                    }, 5000);
                    return;
                }
                removeArmed = false;
                Config.saveCameras(this, new ArrayList<>());
                build();
            }, null));
            rm.setValue(removeArmed ? "OK again: remove all" : cams.size() + "");
        }

        heading("Channels");
        String playlist = Config.playlist(this);
        if (!playlist.isEmpty() && channels.isEmpty() && playlistProblem == null) {
            add(new Row(this, "Reading the playlist…", null, false, null, null));
        } else if (playlistProblem != null) {
            add(new Row(this, playlistProblem, null, false, null, null));
        }
        for (Corner.Source s : channels) {
            Row r = add(new Row(this, s.name, null, false, () -> play(s), null));
            if (s.name.equals(now)) r.setValue("Playing");
            if (first == null) first = r;
        }
        Row pl = add(new Row(this, "Playlist address", "An M3U playlist, the kind TV Hub reads.", false,
                () -> edit("Playlist address", playlist.isEmpty() ? "http://" : playlist,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, v -> {
                            String u = v.trim();
                            if (!u.isEmpty() && !Corner.validUrl(u)) {
                                status.setText("That doesn't look like an address.");
                                return;
                            }
                            Config.savePlaylist(this, u);
                            channels = new ArrayList<>();
                            channelsFrom = "";
                            playlistProblem = null;
                            build();
                            loadPlaylist();
                        }), null));
        pl.setValue(playlist.isEmpty() ? "Not set" : Corner.shown(playlist));

        heading("Window");
        Row place = add(new Row(this, "Corner", null, false, () -> {
            Corner.Place next = Corner.Place.values()[(Config.place(this).ordinal() + 1) % Corner.Place.values().length];
            Config.of(this).edit().putString("place", next.name()).apply();
            CornerService.relayout(this);
            build();
        }, null));
        place.setValue(Corner.placeName(Config.place(this)));
        Row size = add(new Row(this, "Size", "Width, as a share of the screen.", false, () -> {
            Config.of(this).edit().putInt("size", Corner.nextSize(Config.size(this))).apply();
            CornerService.relayout(this);
            build();
        }, null));
        size.setValue(Config.size(this) + " %");
        Row sound = add(new Row(this, "Sound", "Off: the corner never makes a sound.", false, () -> {
            Config.of(this).edit().putBoolean("sound", !Config.sound(this)).apply();
            CornerService.relayout(this);
            build();
        }, null));
        sound.setValue(Config.sound(this) ? "On" : "Off");

        View keep = focused >= 0 && focused < rows.getChildCount() ? rows.getChildAt(focused) : null;
        if (keep != null && keep.isFocusable()) keep.requestFocus();
        else if (first != null) first.requestFocus();
        else place.requestFocus();
    }

    private void play(Corner.Source s) {
        if (!CornerService.canDraw(this)) {
            askOverlay();
            return;
        }
        CornerService.play(this, s.name, s.url);
        // Back to what was playing, with the window over it.
        moveTaskToBack(true);
    }

    private void addCamera() {
        edit("Camera name", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, name ->
                edit("Camera address", "rtsp://", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, url -> {
                    if (!Corner.validUrl(url.trim())) {
                        status.setText("That doesn't look like a stream address.");
                        return;
                    }
                    List<Corner.Source> cams = new ArrayList<>(Config.cameras(this));
                    if (cams.size() >= Corner.MAX_CAMERAS) return;
                    cams.add(new Corner.Source(name.trim(), url.trim(), true));
                    Config.saveCameras(this, cams);
                    build();
                }));
    }

    private void askOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException e) {
            status.setText("This TV has no screen for it. From a computer: adb shell appops set " + getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow");
        }
    }

    private interface Done {
        void done(String value);
    }

    private void edit(String title, String start, int type, Done done) {
        EditText field = new EditText(this);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setText(start);
        field.setSelection(start.length());
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(field)
                .setPositiveButton("Save", (d, w) -> done.done(field.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
        field.requestFocus();
    }

    private void heading(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(13);
        h.setTextColor(0xFFFF9F43);
        h.setPadding(px(16), px(18), 0, px(4));
        rows.addView(h);
    }

    private Row add(Row r) {
        rows.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
