package io.github.maxhayim.cornerplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
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

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tile and module card open this: your cameras, your playlist's channels and the TV's inputs; OK plays one in the corner and
 * leaves this screen, so you're back to what you were watching with the window over it. Open the card again to
 * switch or stop. The window's corner, size and sound are set here too.
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;
    private List<Corner.Source> channels = new ArrayList<>();
    private String channelsFrom = "";
    private String playlistProblem;
    /** OK pressed once on Remove cameras: a second press removes them. */
    private boolean removeArmed;
    /** OK pressed once on New token: a second press replaces it. */
    private boolean tokenArmed;

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
        RemoteService.sync(this);
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
            Net.Playlist p = Net.playlist(url);
            main.post(() -> {
                if (isFinishing()) return;
                channelsFrom = url;
                channels = p.channels;
                playlistProblem = p.problem;
                build();
            });
        });
    }

    /** Rebuilds the list; focus stays on the row it was on. */
    private void build() {
        int focused = rows.indexOfChild(rows.getFocusedChild());
        rows.removeAllViews();
        String now = CornerService.playing;
        status.setText(now == null ? "Nothing in the corner. OK on a camera, channel or input plays it."
                : CornerService.doorbell != null ? "In the corner: the doorbell popup for " + CornerService.doorbell + "."
                : CornerService.cycling ? "In the corner: the cameras in turn, now " + now + "."
                : "In the corner: " + now + ".");
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
        if (cams.size() >= 2) {
            Row cy = add(new Row(this, "Cycle cameras", "Each camera in turn, like a security monitor. ◀ ▶ sets how long each shows.",
                    false, () -> {
                        if (!CornerService.canDraw(this)) {
                            askOverlay();
                            return;
                        }
                        CornerService.cycle(this);
                        moveTaskToBack(true);
                    }, d -> {
                        Config.of(this).edit().putInt("cycle", Corner.nextCycle(Config.cycle(this), d)).apply();
                        build();
                    }));
            cy.setValue((CornerService.cycling ? "Cycling, " : "") + Config.cycle(this) + " s each");
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

        heading("Inputs");
        List<Corner.Source> ins = CornerService.inputs(this);
        if (ins.isEmpty()) add(new Row(this, "No HDMI inputs", "This device shows apps none of its inputs.", false, null, null));
        for (Corner.Source s : ins) {
            Row r = add(new Row(this, s.name, null, false, () -> play(s), null));
            if (s.name.equals(now)) r.setValue("Playing");
            if (first == null) first = r;
        }

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
        Row reopen = add(new Row(this, "Reopen after restart", "What was in the corner comes back when the TV starts.",
                false, () -> {
                    Config.of(this).edit().putBoolean("reopen", !Config.reopen(this)).apply();
                    build();
                }, null));
        reopen.setValue(Config.reopen(this) ? "On" : "Off");

        doorbellRows(cams);

        heading("Network control");
        boolean remote = Config.remote(this);
        Row on = add(new Row(this, "Network control", "Home Assistant, a doorbell or any device on your network opens a"
                + " camera or input in the corner, or closes it, with the token below.", false, () -> {
            Config.setRemote(this, !Config.remote(this));
            RemoteService.sync(this);
            main.postDelayed(this::build, 300);
        }, null));
        on.setValue(remote ? "On" : "Off");
        if (remote) {
            String problem = RemoteService.problem;
            add(new Row(this, "Address", problem != null ? problem
                    : "POST /play with name=a camera or input, or url=; POST /doorbell; POST /stop; GET /status.", false, null, null))
                    .setValue(address());
            add(new Row(this, "Token", "Sent as the header Authorization: Bearer and the token.", false, null, null))
                    .setValue(Config.token(this));
            Row nt = add(new Row(this, "New token", "Your automations stop working until they have the new one.", false, () -> {
                if (!tokenArmed) {
                    tokenArmed = true;
                    build();
                    main.postDelayed(() -> {
                        tokenArmed = false;
                        build();
                    }, 5000);
                    return;
                }
                tokenArmed = false;
                Config.newToken(this);
                build();
            }, null));
            if (tokenArmed) nt.setValue("OK again: replace");
        }

        View keep = focused >= 0 && focused < rows.getChildCount() ? rows.getChildAt(focused) : null;
        if (keep != null && keep.isFocusable()) keep.requestFocus();
        else if (first != null) first.requestFocus();
        else place.requestFocus();
    }

    private interface BellChange {
        void change(Corner.Bell b);
    }

    /** Changes one Doorbell setting, saves it and shows it. */
    private Runnable bellSet(BellChange change) {
        return () -> {
            Corner.Bell b = Config.bell(this);
            change.change(b);
            Config.saveBell(this, b);
            build();
        };
    }

    /** ◀ ▶ on Duration (seconds) or Motion cooldown. */
    private Row.Step bellStep(int[] steps, boolean seconds) {
        return d -> {
            Corner.Bell b = Config.bell(this);
            if (seconds) b.seconds = Corner.step(steps, b.seconds, d);
            else b.cooldown = Corner.step(steps, b.cooldown, d);
            Config.saveBell(this, b);
            build();
        };
    }

    /**
     * What a doorbell event from the network shows: on or off, which camera, where, how long, a chime, rings only or
     * motion too, and a test popup that needs no doorbell.
     */
    private void doorbellRows(List<Corner.Source> cams) {
        heading("Doorbell");
        Corner.Bell bell = Config.bell(this);
        add(new Row(this, "Doorbell popups", "A ring (from Home Assistant or another automation, with network control"
                + " on) shows who's at the door in a corner, then gives the corner back.", false,
                bellSet(b -> b.on = !b.on), null)).setValue(bell.on ? "On" : "Off");
        if (!bell.on) return;
        add(new Row(this, "Camera", "Shown with the banner when a request names none. OK picks the next.", false,
                bellSet(b -> {
                    // None, then each camera in turn.
                    int at = -1;
                    for (int i = 0; i < cams.size(); i++) if (cams.get(i).name.equalsIgnoreCase(b.camera)) at = i;
                    b.camera = at + 1 < cams.size() ? cams.get(at + 1).name : "";
                }), null)).setValue(bell.camera.isEmpty() ? "None" : bell.camera);
        add(new Row(this, "Show", "Camera: its picture with the banner over it. Notification only: the banner alone.",
                false, bellSet(b -> b.video = !b.video), null)).setValue(bell.video ? "Camera" : "Notification only");
        add(new Row(this, "Popup corner", null, false, bellSet(b ->
                b.place = Corner.nextBellPlace(b.place)), null))
                .setValue(bell.place == null ? "Same as video" : Corner.placeName(bell.place));
        add(new Row(this, "Popup size", "Width, as a share of the screen.", false,
                bellSet(b -> b.size = Corner.nextBellSize(b.size)), null))
                .setValue(bell.size == 0 ? "Same as video" : bell.size + " %");
        add(new Row(this, "Duration", "How long the popup stays. ◀ ▶ or OK.", false,
                bellSet(b -> b.seconds = Corner.next(Corner.BELL_SECONDS, b.seconds)),
                bellStep(Corner.BELL_SECONDS, true))).setValue(bell.seconds + " s");
        add(new Row(this, "Chime", "A short chime as it pops up, at the TV's notification volume.", false,
                bellSet(b -> b.chime = !b.chime), null)).setValue(bell.chime ? "On" : "Off");
        add(new Row(this, "Triggers", "Motion too, if your automation sends motion events.", false,
                bellSet(b -> b.motion = !b.motion), null)).setValue(bell.motion ? "Ring and motion" : "Ring only");
        if (bell.motion) {
            add(new Row(this, "Motion cooldown", "At most one motion popup this often. ◀ ▶ or OK.", false,
                    bellSet(b -> b.cooldown = Corner.next(Corner.COOLDOWNS, b.cooldown)),
                    bellStep(Corner.COOLDOWNS, false))).setValue(bell.cooldown == 0 ? "None" : bell.cooldown + " s");
        }
        add(new Row(this, "Restore previous", "When the popup ends, what was in the corner comes back. Off: it closes.",
                false, bellSet(b -> b.restore = !b.restore), null)).setValue(bell.restore ? "On" : "Off");
        add(new Row(this, "Test doorbell popup", "Shows the popup now, as a ring would. No doorbell needed.", false,
                this::testDoorbell, null));
    }

    private void testDoorbell() {
        if (!CornerService.canDraw(this)) {
            askOverlay();
            return;
        }
        Corner.Bell b = Config.bell(this);
        Map<String, String> p = new HashMap<>();
        p.put("event", "test");
        p.put("device", b.camera.isEmpty() ? "Front Door" : b.camera);
        List<Corner.Source> sources = new ArrayList<>(Config.cameras(this));
        sources.addAll(CornerService.inputs(this));
        Corner.Ring ring = Corner.Ring.parse(p, b, sources);
        CornerService.ring(this, ring.request(System.currentTimeMillis()), ring.seconds, b.restore, ring.chime);
        moveTaskToBack(true);
    }

    /** This TV's address on the home network, for the network control's requests. */
    private String address() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()) {
                        return "http://" + a.getAddress().getHostAddress() + ":" + RemoteService.PORT;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // No network: the port alone.
        }
        return "Port " + RemoteService.PORT;
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
