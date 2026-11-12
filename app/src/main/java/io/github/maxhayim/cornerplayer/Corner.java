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
        /**
         * A doorbell popup: the doorbell's name, shown in a banner over the camera; null for anything else. With no
         * url, the popup is the banner alone (notification only).
         */
        final String bell;
        /** A doorbell popup for motion rather than a ring. */
        final boolean motion;
        /** When the doorbell event came, in milliseconds since 1970; 0 if not a doorbell popup. */
        final long at;

        Request(String name, String url, int cycle, Place place, int size, Boolean sound) {
            this(name, url, cycle, place, size, sound, null, false, 0);
        }

        Request(String name, String url, int cycle, Place place, int size, Boolean sound, String bell, boolean motion,
                long at) {
            this.name = name;
            this.url = url;
            this.cycle = cycle;
            this.place = place;
            this.size = size;
            this.sound = sound;
            this.bell = bell;
            this.motion = motion;
            this.at = at;
        }

        /** The same, following the app's corner, size and sound. A doorbell popup keeps the Doorbell settings'. */
        Request plain() {
            return bell != null ? this : new Request(name, url, cycle, null, 0, null);
        }

        /** The same doorbell popup with no camera: the banner alone, as when its camera can't be played. */
        Request cardOnly() {
            return new Request(name, null, 0, place, size, sound, bell, motion, at);
        }

        /** A doorbell popup with nothing to play: the banner alone. */
        boolean card() {
            return bell != null && cycle == 0 && url == null;
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
                if (bell != null) o.put("bell", bell);
                if (motion) o.put("motion", true);
                if (at > 0) o.put("at", at);
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
                String bell = o.optString("bell", null);
                if (bell != null && url == null && cycle == 0) {
                    // A doorbell popup with no camera.
                } else if (cycle == 0 && !validUrl(url) && !isInput(url)) {
                    return null;
                }
                int size = o.optInt("size", 0);
                return new Request(o.optString("name", null), url, cycle, parsePlace(o.optString("corner", null)),
                        size >= MIN_SIZE && size <= MAX_SIZE ? size : 0,
                        o.has("sound") ? o.optBoolean("sound") : null,
                        bell, o.optBoolean("motion", false), Math.max(0, o.optLong("at", 0)));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * What the corner shows and what it goes back to. Something opened for a few seconds (a doorbell) is short: when
     * it ends, the corner goes back to what was there before the first short one, or closes if it was empty. A short
     * one over a short one keeps the first's way back, so two doorbells in a row never "restore" the first doorbell.
     */
    static final class Slot {
        Request now;
        private Request back;
        private boolean isShort;
        private boolean restore = true;

        /** Something new in the corner; short ones end by themselves. restore: whether a short one gives the corner back. */
        void open(Request r, boolean temporary, boolean restore) {
            if (temporary) {
                if (!isShort) back = now;
                isShort = true;
                this.restore = restore;
            } else {
                back = null;
                isShort = false;
                this.restore = true;
            }
            now = r;
        }

        boolean isShort() {
            return isShort;
        }

        /** The short one is over: what to show now, or null to close the window. */
        Request end() {
            Request b = isShort && restore ? back : null;
            back = null;
            isShort = false;
            restore = true;
            now = b;
            return b;
        }

        /** The short one's camera can't be played: keep its banner and its timer. */
        void replace(Request r) {
            now = r;
        }
    }

    /** What a doorbell, Home Assistant or another automation says happened. */
    enum Event { RING, MOTION, TEST, DISMISS }

    /** ring (or ding, press, doorbell), motion, test, dismiss (or clear); null for anything else. */
    static Event parseEvent(String text) {
        if (text == null || text.trim().isEmpty()) return Event.RING;
        switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "ring": case "ding": case "press": case "pressed": case "doorbell": return Event.RING;
            case "motion": return Event.MOTION;
            case "test": return Event.TEST;
            case "dismiss": case "clear": return Event.DISMISS;
            default: return null;
        }
    }

    /**
     * Which doorbell events become a popup. A ring repeated within RING_REPEAT_MS (one press sent twice, or a
     * child pressing again and again) changes nothing: the popup is already up. A later ring shows it again with a
     * fresh timer. Motion shows only when switched on, never over a ring's popup, and at most once per cooldown.
     * Requests come one at a time, so this needs no locking.
     */
    static final class Gate {
        static final long RING_REPEAT_MS = 5000;
        private static final long NEVER = Long.MIN_VALUE / 4;
        private long lastRing = NEVER, lastMotion = NEVER, ringUntil = NEVER;

        /** Null: show it (and it's remembered). Otherwise why it isn't shown. */
        String check(Event e, long nowMs, int seconds, int motionCooldownSeconds, boolean motionOn) {
            if (e == Event.RING) {
                if (nowMs - lastRing < RING_REPEAT_MS) return "Rang again within a few seconds: the popup is already up.";
                lastRing = nowMs;
                ringUntil = nowMs + seconds * 1000L;
                return null;
            }
            if (e == Event.MOTION) {
                if (!motionOn) return "Motion popups are off on the TV (Doorbell → Triggers: Ring only).";
                if (nowMs < ringUntil) return "A doorbell ring's popup is up.";
                if (nowMs - lastMotion < motionCooldownSeconds * 1000L) return "Motion cooldown: shown less than "
                        + motionCooldownSeconds + " seconds ago.";
                lastMotion = nowMs;
                return null;
            }
            return null;
        }

        /** The popup was closed: motion may show again (its cooldown still counts). */
        void dismissed() {
            ringUntil = NEVER;
        }
    }

    /** Popup lengths offered on the TV, in seconds. */
    static final int[] BELL_SECONDS = {10, 15, 30, 45, 60, 120};
    /** Motion cooldowns offered on the TV, in seconds. */
    static final int[] COOLDOWNS = {0, 30, 60, 120, 300, 600};
    /** A doorbell popup asked for from the network: this long at least, and at most. */
    static final int MIN_BELL_SECONDS = 5, MAX_BELL_SECONDS = 600;
    static final int MAX_LABEL = 40;

    /** The Doorbell settings, kept as one JSON value; anything missing or out of range is its default. */
    static final class Bell {
        boolean on = true;
        /** The camera to show, by name; empty for none (the banner alone). */
        String camera = "";
        Place place = Place.TOP_RIGHT;
        int size = SIZES[1];
        int seconds = 45;
        /** A short chime when it pops up. Off: Corner Player is silent unless asked. */
        boolean chime;
        /** Motion popups too, not only rings. */
        boolean motion;
        int cooldown = 60;
        /** Show the camera; off, the banner alone. */
        boolean video = true;
        /** Give the corner back to what was in it when the popup ends; off, the corner closes. */
        boolean restore = true;

        String toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("on", on).put("camera", camera).put("corner", place.name()).put("size", size)
                        .put("seconds", seconds).put("chime", chime).put("motion", motion).put("cooldown", cooldown)
                        .put("video", video).put("restore", restore);
            } catch (Exception ignored) {
                // Plain values only.
            }
            return o.toString();
        }

        static Bell fromJson(String json) {
            Bell b = new Bell();
            JSONObject o;
            try {
                o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            } catch (Exception e) {
                return b;
            }
            b.on = o.optBoolean("on", b.on);
            b.camera = label(o.optString("camera", ""), "");
            Place p = parsePlace(o.optString("corner", null));
            if (p != null) b.place = p;
            int size = o.optInt("size", b.size);
            if (contains(SIZES, size)) b.size = size;
            int seconds = o.optInt("seconds", b.seconds);
            if (contains(BELL_SECONDS, seconds)) b.seconds = seconds;
            b.chime = o.optBoolean("chime", b.chime);
            b.motion = o.optBoolean("motion", b.motion);
            int cooldown = o.optInt("cooldown", b.cooldown);
            if (contains(COOLDOWNS, cooldown)) b.cooldown = cooldown;
            b.video = o.optBoolean("video", b.video);
            b.restore = o.optBoolean("restore", b.restore);
            return b;
        }
    }

    /**
     * A doorbell request, checked: either an error for the caller (a 400), or what to show. Parameters are those of
     * POST /doorbell; anything left out follows the Doorbell settings.
     */
    static final class Ring {
        String error;
        Event event;
        Place place;
        int size, seconds, cooldown;
        boolean chime;
        /** The camera's own sound; null: off, as a doorbell popup is silent unless asked. */
        Boolean audio;
        /** What to play: a camera or input, a url given (kept in memory only), or null for the banner alone. */
        Source source;
        boolean fromUrl;
        String device;
        /** Something the caller should know, though the popup shows (a camera name that wasn't found). */
        String warning;

        /** sources: the cameras, then the TV's inputs, to look a camera name up in. */
        static Ring parse(Map<String, String> p, Bell bell, List<Source> sources) {
            Ring r = new Ring();
            r.event = parseEvent(p.get("event"));
            if (r.event == null) return r.fail("event must be ring, motion, test or dismiss.");
            if (r.event == Event.DISMISS) return r;
            r.place = bell.place;
            if (p.get("corner") != null && (r.place = parsePlace(p.get("corner"))) == null) {
                return r.fail("corner must be bottom-right, bottom-left, top-left or top-right.");
            }
            r.size = bell.size;
            if (p.get("size") != null && (r.size = parseSize(p.get("size"))) < 0) {
                return r.fail("size must be " + MIN_SIZE + " to " + MAX_SIZE + " (percent of the screen's width).");
            }
            r.seconds = bell.seconds;
            if (p.get("seconds") != null && (r.seconds = parseSeconds(p.get("seconds"), MIN_BELL_SECONDS, MAX_BELL_SECONDS)) < 0) {
                return r.fail("seconds must be " + MIN_BELL_SECONDS + " to " + MAX_BELL_SECONDS + ".");
            }
            r.chime = bell.chime;
            if (p.get("sound") != null) {
                Boolean s = parseSound(p.get("sound"));
                if (s == null) return r.fail("sound must be on or off.");
                r.chime = s;
            }
            if (p.get("audio") != null && (r.audio = parseSound(p.get("audio"))) == null) {
                return r.fail("audio must be on or off.");
            }
            boolean video = bell.video;
            if (p.get("video") != null) {
                Boolean v = parseSound(p.get("video"));
                if (v == null) return r.fail("video must be on or off.");
                video = video && v;
            }
            r.cooldown = bell.cooldown;
            if (p.get("cooldown") != null && (r.cooldown = parseSeconds(p.get("cooldown"), 0, MAX_SECONDS)) < 0) {
                return r.fail("cooldown must be 0 to " + MAX_SECONDS + " seconds.");
            }
            String url = p.get("url");
            url = url == null || url.trim().isEmpty() ? null : url.trim();
            if (url != null && (!validUrl(url) || selfOrLinkLocal(url))) {
                return r.fail("url must be an rtsp://, http:// or https:// address on your network.");
            }
            String camera = p.get("camera");
            camera = camera == null || camera.trim().isEmpty() ? null : camera.trim();
            if (camera != null && camera.length() > 200) return r.fail("camera is too long.");
            String device = p.get("device");
            if (device != null && device.length() > 200) return r.fail("device is too long.");

            if (video) {
                if (url != null) {
                    r.source = new Source(shown(url), url, true);
                    r.fromUrl = true;
                } else {
                    String want = camera != null ? camera : bell.camera.isEmpty() ? null : bell.camera;
                    if (want != null) {
                        r.source = find(sources, want);
                        if (r.source == null) r.warning = "No camera or input named " + label(want, "") + ": the banner alone.";
                    }
                }
            }
            String fallback = r.source != null && !r.fromUrl ? r.source.name : camera != null ? label(camera, "Doorbell") : "Doorbell";
            r.device = label(device, fallback);
            return r;
        }

        private Ring fail(String message) {
            error = message;
            return this;
        }

        /** The popup to open, at this time. */
        Request request(long at) {
            return new Request(source == null ? device : source.name, source == null ? null : source.url, 0, place, size,
                    audio, device, event == Event.MOTION, at);
        }

        /** "url", "camera" or "notification": where the picture comes from. */
        String sourceKind() {
            return source == null ? "notification" : fromUrl ? "url" : "camera";
        }
    }

    static boolean contains(int[] steps, int v) {
        for (int s : steps) if (s == v) return true;
        return false;
    }

    /** The next or previous of steps, stopping at the ends; the second step for a value not among them. */
    static int step(int[] steps, int current, int direction) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) return steps[Math.max(0, Math.min(steps.length - 1, i + direction))];
        }
        return steps[Math.min(1, steps.length - 1)];
    }

    /** The next of steps, going round. */
    static int next(int[] steps, int current) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == current) return steps[(i + 1) % steps.length];
        return steps[0];
    }

    /**
     * A name to show (a doorbell's): control characters out, spaces collapsed, at most MAX_LABEL characters;
     * fallback if nothing is left.
     */
    static String label(String text, String fallback) {
        if (text == null) return fallback;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            b.append(Character.isISOControl(ch) || Character.isWhitespace(ch) ? ' ' : ch);
        }
        String t = b.toString().trim().replaceAll(" {2,}", " ");
        if (t.isEmpty()) return fallback;
        return t.length() > MAX_LABEL ? t.substring(0, MAX_LABEL - 1).trim() + "…" : t;
    }

    /** Whole seconds from min to max; -1 for anything else. */
    static int parseSeconds(String text, int min, int max) {
        try {
            int n = Integer.parseInt(text.trim());
            return n >= min && n <= max ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Whether an address points at the TV itself (localhost, 127.x, ::1, 0.0.0.0) or a link-local address
     * (169.254.x, fe80::): a doorbell request may not make the TV fetch from its own services. Unreadable addresses
     * count as such.
     */
    static boolean selfOrLinkLocal(String url) {
        String host;
        try {
            host = new URI(url.trim()).getHost();
        } catch (Exception e) {
            return true;
        }
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("0.0.0.0")
                || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1") || h.equals("::") || h.startsWith("169.254.")
                || h.startsWith("fe80:");
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
        return step(CYCLE_STEPS, current, direction);
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
        return contains(SIZES, current) ? next(SIZES, current) : SIZES[1];
    }
}
