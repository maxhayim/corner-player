package io.github.maxhayim.cornerplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.List;

/**
 * Cameras, the playlist address, the window's settings and network control, in the app's own storage (backups are
 * off). Cameras, the playlist and the network token arrive typed on the TV or as config.json pushed by install.sh,
 * read once and deleted.
 */
final class Config {
    static final String PUSHED = "config.json";

    private Config() {
    }

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("corner", Context.MODE_PRIVATE);
    }

    static List<Corner.Source> cameras(Context c) {
        return Corner.cameras(of(c).getString("cameras", "[]"));
    }

    static void saveCameras(Context c, List<Corner.Source> cams) {
        of(c).edit().putString("cameras", Corner.camerasJson(cams)).apply();
    }

    static String playlist(Context c) {
        return of(c).getString("playlist", "");
    }

    static void savePlaylist(Context c, String url) {
        of(c).edit().putString("playlist", url == null ? "" : url.trim()).apply();
    }

    static Corner.Place place(Context c) {
        try {
            return Corner.Place.valueOf(of(c).getString("place", Corner.Place.BOTTOM_RIGHT.name()));
        } catch (IllegalArgumentException e) {
            return Corner.Place.BOTTOM_RIGHT;
        }
    }

    static int size(Context c) {
        return of(c).getInt("size", Corner.SIZES[1]);
    }

    /** Sound off by default: the corner never makes noise unless asked to. */
    static boolean sound(Context c) {
        return of(c).getBoolean("sound", false);
    }

    /** Seconds per camera when cycling. */
    static int cycle(Context c) {
        return of(c).getInt("cycle", Corner.CYCLE_STEPS[1]);
    }

    /** Off by default: after a restart the corner stays closed unless this is on. */
    static boolean reopen(Context c) {
        return of(c).getBoolean("reopen", false);
    }

    /** What was in the corner (not a short one from the network), to reopen after a restart; null if nothing. */
    static Corner.Request last(Context c) {
        String json = of(c).getString("last", "");
        return json.isEmpty() ? null : Corner.Request.fromJson(json);
    }

    static void saveLast(Context c, Corner.Request r) {
        of(c).edit().putString("last", r == null ? "" : r.toJson()).apply();
    }

    /** Network control: off unless switched on, on the TV or in a pushed config.json. */
    static boolean remote(Context c) {
        return of(c).getBoolean("remote", false);
    }

    static void setRemote(Context c, boolean on) {
        of(c).edit().putBoolean("remote", on).apply();
    }

    /** The token requests must carry; made the first time it's needed. */
    static String token(Context c) {
        String t = of(c).getString("token", "");
        return Corner.validToken(t) ? t : newToken(c);
    }

    static String newToken(Context c) {
        String t = Corner.newToken(new SecureRandom());
        of(c).edit().putString("token", t).apply();
        return t;
    }

    /**
     * Takes a pushed config.json ({"cameras": [{"name", "url"}], "playlist": url, "remote": true, "token": ...}),
     * and deletes it.
     */
    static boolean importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return false;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray cams = o.optJSONArray("cameras");
            if (cams != null) saveCameras(c, Corner.cameras(cams.toString()));
            if (o.has("playlist")) savePlaylist(c, o.optString("playlist"));
            if (o.has("remote")) setRemote(c, o.optBoolean("remote"));
            String token = o.optString("token", "");
            if (Corner.validToken(token)) of(c).edit().putString("token", token).apply();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
