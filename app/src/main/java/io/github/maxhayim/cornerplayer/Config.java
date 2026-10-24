package io.github.maxhayim.cornerplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Cameras, the playlist address and the window's settings, in the app's own storage (backups are off). Cameras
 * and the playlist arrive typed on the TV or as config.json pushed by install.sh, read once and deleted.
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

    /** Takes a pushed config.json ({"cameras": [{"name", "url"}], "playlist": url}), and deletes it. */
    static boolean importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return false;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray cams = o.optJSONArray("cameras");
            if (cams != null) saveCameras(c, Corner.cameras(cams.toString()));
            if (o.has("playlist")) savePlaylist(c, o.optString("playlist"));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
