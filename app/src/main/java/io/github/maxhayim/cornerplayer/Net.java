package io.github.maxhayim.cornerplayer;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The few HTTP requests Corner Player makes itself: the playlist, and pictures from cameras that send stills. */
final class Net {
    private static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;

    private Net() {
    }

    /** A playlist's channels, or why there are none. */
    static final class Playlist {
        final List<Corner.Source> channels;
        final String problem;

        Playlist(List<Corner.Source> channels, String problem) {
            this.channels = channels;
            this.problem = problem;
        }
    }

    /** Opens an http(s) address; a user name and password in it go in a Basic Authorization header. */
    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Corner.withoutUserInfo(url)).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        String user = Corner.userInfo(url);
        if (user != null) {
            c.setRequestProperty("Authorization",
                    "Basic " + Base64.encodeToString(user.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return c;
    }

    /** All of a reply, or null if it is larger than max. */
    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    /** Reads a playlist; call it off the main thread. */
    static Playlist playlist(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            int code = c.getResponseCode();
            if (code / 100 != 2) return new Playlist(new ArrayList<>(), "The playlist address answered with error " + code + ".");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && out.size() < MAX_PLAYLIST_BYTES; ) out.write(buf, 0, n);
                List<Corner.Source> found = Corner.parseM3u(out.toString(StandardCharsets.UTF_8.name()));
                return new Playlist(found, found.isEmpty() ? "No channels in that playlist." : null);
            }
        } catch (Exception e) {
            return new Playlist(new ArrayList<>(), "Can't read the playlist at that address.");
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
