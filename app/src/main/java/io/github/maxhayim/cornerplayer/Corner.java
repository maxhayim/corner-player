package io.github.maxhayim.cornerplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Sources, playlists and the window's place, with no Android types, so the JVM tests cover them: reading an M3U
 * playlist, checking stream addresses, hiding camera passwords on screen, where the corner window goes, and the
 * network control's requests and token.
 */
final class Corner {
    /** A playlist larger than this is cut off, so a big IPTV list can't make the screen slow. */
    static final int MAX_CHANNELS = 500;
    static final int MAX_CAMERAS = 20;
    /** Window widths, in percent of the screen's. */
    static final int[] SIZES = {25, 33, 40};
    /** Network control: how long a source opened from the network may stay up before it closes by itself. */
    static final int MAX_SECONDS = 3600;
    /** Token letters: lowercase and digits, without the ones that look alike on a TV (0/o, 1/l/i). */
    private static final String TOKEN_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789";
    static final int TOKEN_LENGTH = 24;
    /** A TV input (HDMI and the like) travels as "input:" and its id, like an address; never sent to a network. */
    static final String INPUT = "input:";

    enum Place { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

    static final class Source {
        final String name;
        final String url;
        final boolean camera;

        Source(String name, String url, boolean camera) {
            this.name = name;
            this.url = url;
            this.camera = camera;
        }
    }

    private Corner() {
    }

    /** Channels from an M3U or M3U8 playlist: each #EXTINF's title and the address on the next line. */
    static List<Source> parseM3u(String text) {
        List<Source> out = new ArrayList<>();
        if (text == null) return out;
        String name = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : null;
            } else if (!line.startsWith("#")) {
                if (validUrl(line)) out.add(new Source(name == null || name.isEmpty() ? shown(line) : name, line, false));
                name = null;
                if (out.size() >= MAX_CHANNELS) break;
            }
        }
        return out;
    }

    static boolean validUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        return (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("rtsp://") || u.startsWith("rtsps://"))
                && !u.contains(" ") && u.indexOf("://") + 3 < u.length();
    }

    static String inputUrl(String inputId) {
        return INPUT + inputId;
    }

    static boolean isInput(String url) {
        return url != null && url.startsWith(INPUT) && url.length() > INPUT.length();
    }

    static String inputId(String url) {
        return isInput(url) ? url.substring(INPUT.length()) : null;
    }

    /** The address as it may be shown: scheme, host and port, never the user name or password in it. */
    static String shown(String url) {
        if (url == null) return "";
        int s = url.indexOf("://");
        if (s < 0) return url;
        String rest = url.substring(s + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        return url.substring(0, s + 3) + host;
    }

    /** x, y, width, height of the window: 16:9, the chosen width, a margin from the screen's edges. */
    static int[] rect(int screenW, int screenH, Place place, int sizePercent, int margin) {
        int w = Math.round(screenW * sizePercent / 100f);
        int h = Math.round(w * 9 / 16f);
        boolean left = place == Place.TOP_LEFT || place == Place.BOTTOM_LEFT;
        boolean top = place == Place.TOP_LEFT || place == Place.TOP_RIGHT;
        int x = left ? margin : screenW - margin - w;
        int y = top ? margin : screenH - margin - h;
        return new int[]{x, y, w, h};
    }

    /** Cameras as stored: [{"name": ..., "url": ...}]. Bad entries are skipped. */
    static List<Source> cameras(String json) {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < a.length() && out.size() < MAX_CAMERAS; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (!validUrl(url)) continue;
                String name = o.optString("name", "").trim();
                out.add(new Source(name.isEmpty() ? shown(url) : name, url, true));
            }
        } catch (Exception ignored) {
            // Nothing readable: no cameras.
        }
        return out;
    }

    static String camerasJson(List<Source> cams) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < cams.size(); i++) {
            if (i > 0) b.append(',');
            b.append("{\"name\":").append(JSONObject.quote(cams.get(i).name))
                    .append(",\"url\":").append(JSONObject.quote(cams.get(i).url)).append('}');
        }
        return b.append(']').toString();
    }

    /** The source with this name, ignoring case and spaces around it; null if there is none. */
    static Source find(List<Source> sources, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        for (Source s : sources) if (s.name.trim().equalsIgnoreCase(name.trim())) return s;
        return null;
    }

    /** Parameters of a query string or form body: a=1&b=two%20words. Later keys win; bad escapes are skipped. */
    static Map<String, String> query(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, StandardCharsets.UTF_8.name()), URLDecoder.decode(v, StandardCharsets.UTF_8.name()));
            } catch (Exception ignored) {
                // Not decodable: left out.
            }
        }
        return out;
    }

    /** Seconds before a source opened from the network closes: 0 (stays up) to MAX_SECONDS. */
    static int seconds(String text) {
        try {
            return Math.max(0, Math.min(MAX_SECONDS, Integer.parseInt(text == null ? "0" : text.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String newToken(Random random) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < TOKEN_LENGTH; i++) b.append(TOKEN_LETTERS.charAt(random.nextInt(TOKEN_LETTERS.length())));
        return b.toString();
    }

    /** A token pushed in config.json: long enough, and only letters and digits, so it can sit in a header. */
    static boolean validToken(String token) {
        return token != null && token.length() >= 16 && token.length() <= 128 && token.matches("[A-Za-z0-9]+");
    }

    /** Whether an Authorization header carries the token, compared in constant time. */
    static boolean authorized(String token, String header) {
        if (!validToken(token) || header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    static String placeName(Place p) {
        switch (p) {
            case TOP_LEFT: return "Top left";
            case TOP_RIGHT: return "Top right";
            case BOTTOM_LEFT: return "Bottom left";
            default: return "Bottom right";
        }
    }

    static int nextSize(int current) {
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == current) return SIZES[(i + 1) % SIZES.length];
        return SIZES[1];
    }
}
