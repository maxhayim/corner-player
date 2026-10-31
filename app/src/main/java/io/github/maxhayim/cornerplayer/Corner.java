package io.github.maxhayim.cornerplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
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
 * playlist, checking stream addresses, hiding camera passwords on screen, where the corner window goes, the
 * network control's requests and token, what to play with its own window settings, and pictures from cameras that
 * send stills or MJPEG.
 */
final class Corner {
    /** A playlist larger than this is cut off, so a big IPTV list can't make the screen slow. */
    static final int MAX_CHANNELS = 500;
    static final int MAX_CAMERAS = 20;
    /** Window widths, in percent of the screen's. */
    static final int[] SIZES = {25, 33, 40};
    /** A width asked for from the network, in percent: anything in this range, not only SIZES. */
    static final int MIN_SIZE = 15, MAX_SIZE = 60;
    /** Seconds per camera when cycling. */
    static final int[] CYCLE_STEPS = {5, 10, 20, 30, 60};
    /** One picture from a camera, still or MJPEG frame: anything larger is dropped. */
    static final int MAX_PICTURE_BYTES = 8 * 1024 * 1024;
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

    /**
     * What to play and how: a source (or every camera in turn, every cycle seconds), with the window settings this
     * one asked for. Null settings follow the app's. Kept as JSON to reopen it after a restart.
     */
    static final class Request {
        final String name;
        final String url;
        /** Above 0: every camera in turn, this many seconds each; name and url are then unused. */
        final int cycle;
        final Place place;
        /** Width in percent; 0 follows the app. */
        final int size;
        final Boolean sound;

        Request(String name, String url, int cycle, Place place, int size, Boolean sound) {
            this.name = name;
            this.url = url;
            this.cycle = cycle;
            this.place = place;
            this.size = size;
            this.sound = sound;
        }

        /** The same, following the app's corner, size and sound. */
        Request plain() {
            return new Request(name, url, cycle, null, 0, null);
        }

        String toJson() {
            JSONObject o = new JSONObject();
            try {
                if (name != null) o.put("name", name);
                if (url != null) o.put("url", url);
                if (cycle > 0) o.put("cycle", cycle);
                if (place != null) o.put("corner", place.name());
                if (size > 0) o.put("size", size);
                if (sound != null) o.put("sound", sound);
            } catch (Exception ignored) {
                // Plain values only.
            }
            return o.toString();
        }

        /** Null for anything that can't be played. */
        static Request fromJson(String json) {
            try {
                JSONObject o = new JSONObject(json);
                int cycle = Math.max(0, o.optInt("cycle", 0));
                String url = o.optString("url", null);
                if (cycle == 0 && !validUrl(url) && !isInput(url)) return null;
                int size = o.optInt("size", 0);
                return new Request(o.optString("name", null), url, cycle, parsePlace(o.optString("corner", null)),
                        size >= MIN_SIZE && size <= MAX_SIZE ? size : 0,
                        o.has("sound") ? o.optBoolean("sound") : null);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * MJPEG: the JPEG pictures in a camera's multipart stream, found by their start and end markers rather than the
     * part headers, which cameras write in many ways.
     */
    static final class Jpegs {
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private boolean inFrame;
        private int last = -1;

        /** The pictures completed by these bytes. */
        List<byte[]> feed(byte[] b, int n) {
            List<byte[]> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int x = b[i] & 0xFF;
                if (!inFrame) {
                    if (last == 0xFF && x == 0xD8) {
                        inFrame = true;
                        frame.reset();
                        frame.write(0xFF);
                        frame.write(0xD8);
                    }
                } else {
                    frame.write(x);
                    if (last == 0xFF && x == 0xD9) {
                        out.add(frame.toByteArray());
                        inFrame = false;
                        frame.reset();
                    } else if (frame.size() > MAX_PICTURE_BYTES) {
                        inFrame = false;
                        frame.reset();
                    }
                }
                last = x;
            }
            return out;
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

    /** bottom-right, bottom_right, BottomRight and the like; null for anything else. */
    static Place parsePlace(String text) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        for (Place p : Place.values()) if (p.name().toLowerCase(Locale.ROOT).replace("_", "").equals(t)) return p;
        return null;
    }

    /** A width in percent, MIN_SIZE to MAX_SIZE; -1 for anything else. */
    static int parseSize(String text) {
        try {
            int n = Integer.parseInt(text.trim().replace("%", ""));
            return n >= MIN_SIZE && n <= MAX_SIZE ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** on/off, true/false, 1/0, yes/no; null for anything else. */
    static Boolean parseSound(String text) {
        if (text == null) return null;
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "1": case "yes": return true;
            case "off": case "false": case "0": case "no": return false;
            default: return null;
        }
    }

    static int nextCycle(int current, int direction) {
        for (int i = 0; i < CYCLE_STEPS.length; i++) {
            if (CYCLE_STEPS[i] == current) {
                return CYCLE_STEPS[Math.max(0, Math.min(CYCLE_STEPS.length - 1, i + direction))];
            }
        }
        return CYCLE_STEPS[1];
    }

    /** Whether an address is surely video by its scheme or ending, so it needs no look at what it sends first. */
    static boolean surelyVideo(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("rtsp")) return true;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        for (String end : new String[]{".m3u8", ".m3u", ".mpd", ".ts", ".mp4", ".m4v", ".mkv", ".webm", ".mov"}) {
            if (u.endsWith(end)) return true;
        }
        return false;
    }

    enum Kind { VIDEO, STILL, MJPEG }

    /** What an http(s) answer is, by its Content-Type: a picture to refresh, an MJPEG stream, or video. */
    static Kind kind(String contentType) {
        if (contentType == null) return Kind.VIDEO;
        String t = contentType.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("image/")) return Kind.STILL;
        if (t.startsWith("multipart/x-mixed-replace")) return Kind.MJPEG;
        return Kind.VIDEO;
    }

    /** The user name and password in an address ("user:password", unescaped), or null. */
    static String userInfo(String url) {
        try {
            String u = new URI(url.trim()).getUserInfo();
            return u == null || u.isEmpty() ? null : u;
        } catch (Exception e) {
            return null;
        }
    }

    /** The address without its user name and password, which go in a header instead. */
    static String withoutUserInfo(String url) {
        if (url == null) return null;
        int s = url.indexOf("://");
        if (s < 0) return url;
        int slash = url.indexOf('/', s + 3);
        int end = slash < 0 ? url.length() : slash;
        int at = url.lastIndexOf('@', end - 1);
        return at > s ? url.substring(0, s + 3) + url.substring(at + 1) : url;
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
