package io.github.maxhayim.cornerplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class CornerTest {
    @Test
    public void readsAnM3uPlaylist() {
        String m3u = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"news.us\" tvg-logo=\"http://x/logo.png\" group-title=\"News\",News 24\n"
                + "http://192.168.1.5:8080/live/news.m3u8\r\n"
                + "#EXTVLCOPT:http-user-agent=VLC\n"
                + "#EXTINF:-1,Kids, Cartoons\n"
                + "https://example.com/kids.m3u8\n"
                + "#EXTINF:-1,Broken\n"
                + "not a url\n"
                + "rtsp://192.168.1.20/cam\n";
        List<Corner.Source> ch = Corner.parseM3u(m3u);
        assertEquals(3, ch.size());
        assertEquals("News 24", ch.get(0).name);
        assertEquals("http://192.168.1.5:8080/live/news.m3u8", ch.get(0).url);
        assertEquals("the title is after the last comma", "Cartoons", ch.get(1).name);
        assertEquals("a line without #EXTINF is named by its host", "rtsp://192.168.1.20", ch.get(2).name);
        assertFalse(ch.get(0).camera);
    }

    @Test
    public void bigPlaylistsAreCapped() {
        StringBuilder b = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 900; i++) b.append("#EXTINF:-1,Ch ").append(i).append("\nhttp://h/").append(i).append('\n');
        assertEquals(Corner.MAX_CHANNELS, Corner.parseM3u(b.toString()).size());
    }

    @Test
    public void addresses() {
        assertTrue(Corner.validUrl("rtsp://user:pw@192.168.1.20:554/stream2"));
        assertTrue(Corner.validUrl("https://cam.example/live.m3u8"));
        assertFalse(Corner.validUrl("file:///sdcard/x.mp4"));
        assertFalse(Corner.validUrl("rtsp://"));
        assertFalse(Corner.validUrl("http://a b"));
    }

    @Test
    public void passwordsNeverShow() {
        assertEquals("rtsp://192.168.1.20:554", Corner.shown("rtsp://admin:s3cret@192.168.1.20:554/Streaming/101"));
        assertEquals("http://h", Corner.shown("http://h/path?token=abc"));
    }

    @Test
    public void windowPlaces() {
        assertArrayEquals(new int[]{1254, 691, 634, 357}, Corner.rect(1920, 1080, Corner.Place.BOTTOM_RIGHT, 33, 32));
        assertArrayEquals(new int[]{32, 32, 480, 270}, Corner.rect(1920, 1080, Corner.Place.TOP_LEFT, 25, 32));
        int[] r = Corner.rect(3840, 2160, Corner.Place.BOTTOM_LEFT, 40, 64);
        assertEquals(64, r[0]);
        assertEquals(2160 - 64 - r[3], r[1]);
        assertEquals(r[2] * 9 / 16, r[3]);
    }

    @Test
    public void camerasRoundTrip() {
        List<Corner.Source> cams = Arrays.asList(new Corner.Source("Nursery", "rtsp://u:p@192.168.1.40/s2", true),
                new Corner.Source("Door \"front\"", "http://192.168.1.41/live.m3u8", true));
        List<Corner.Source> back = Corner.cameras(Corner.camerasJson(cams));
        assertEquals(2, back.size());
        assertEquals("Door \"front\"", back.get(1).name);
        assertEquals("rtsp://u:p@192.168.1.40/s2", back.get(0).url);
        assertTrue(back.get(0).camera);
    }

    @Test
    public void badCameraEntriesAreSkipped() {
        assertEquals(1, Corner.cameras("[{\"name\":\"A\",\"url\":\"ftp://x\"},{\"url\":\"rtsp://192.168.1.40/s\"},7]").size());
        assertEquals(0, Corner.cameras("not json").size());
        assertEquals("rtsp://192.168.1.40", Corner.cameras("[{\"url\":\"rtsp://192.168.1.40/s\"}]").get(0).name);
    }

    @Test
    public void inputsAreNotAddresses() {
        String hdmi = Corner.inputUrl("com.example.tvinput/.HdmiService/HW2");
        assertTrue(Corner.isInput(hdmi));
        assertEquals("com.example.tvinput/.HdmiService/HW2", Corner.inputId(hdmi));
        assertFalse("an input is never fetched", Corner.validUrl(hdmi));
        assertFalse(Corner.isInput("input:"));
        assertFalse(Corner.isInput("rtsp://192.168.1.40/s"));
        assertEquals(0, Corner.parseM3u("#EXTINF:-1,HDMI\n" + hdmi + "\n").size());
    }

    @Test
    public void networkControlFindsSourcesByName() {
        List<Corner.Source> cams = Arrays.asList(new Corner.Source("Front door", "rtsp://192.168.1.40/s", true),
                new Corner.Source("Nursery", "rtsp://192.168.1.41/s", true));
        assertEquals("Front door", Corner.find(cams, "  front DOOR ").name);
        assertNull(Corner.find(cams, "Garage"));
        assertNull(Corner.find(cams, ""));
        assertNull(Corner.find(cams, null));
    }

    @Test
    public void networkControlReadsQueries() {
        Map<String, String> q = Corner.query("name=Front+door&seconds=60&url=rtsp%3A%2F%2F192.168.1.40%2Fs&bad=%zz");
        assertEquals("Front door", q.get("name"));
        assertEquals("60", q.get("seconds"));
        assertEquals("rtsp://192.168.1.40/s", q.get("url"));
        assertFalse("a bad escape is left out", q.containsKey("bad"));
        assertTrue(Corner.query(null).isEmpty());
    }

    @Test
    public void networkControlSecondsAreCapped() {
        assertEquals(60, Corner.seconds("60"));
        assertEquals(0, Corner.seconds("-5"));
        assertEquals(0, Corner.seconds("soon"));
        assertEquals(0, Corner.seconds(null));
        assertEquals(Corner.MAX_SECONDS, Corner.seconds("999999"));
    }

    @Test
    public void networkControlNeedsTheToken() {
        String token = Corner.newToken(new Random(7));
        assertEquals(Corner.TOKEN_LENGTH, token.length());
        assertTrue(Corner.validToken(token));
        assertFalse("no look-alike letters", token.matches(".*[01ilo].*"));
        assertTrue(Corner.authorized(token, "Bearer " + token));
        assertTrue(Corner.authorized(token, "bearer " + token + " "));
        assertFalse(Corner.authorized(token, token));
        assertFalse(Corner.authorized(token, "Bearer " + token + "x"));
        assertFalse(Corner.authorized(token, null));
        assertFalse("no token set: nobody gets in", Corner.authorized("", "Bearer "));
        assertFalse(Corner.validToken("short"));
        assertFalse(Corner.validToken("has spaces in it, sixteen+"));
    }

    @Test
    public void windowOptionsFromTheNetwork() {
        assertEquals(Corner.Place.TOP_LEFT, Corner.parsePlace("top-left"));
        assertEquals(Corner.Place.BOTTOM_RIGHT, Corner.parsePlace("Bottom_Right"));
        assertEquals(Corner.Place.TOP_RIGHT, Corner.parsePlace("topright"));
        assertNull(Corner.parsePlace("middle"));
        assertNull(Corner.parsePlace(null));
        assertEquals(40, Corner.parseSize("40"));
        assertEquals(50, Corner.parseSize("50%"));
        assertEquals(-1, Corner.parseSize("90"));
        assertEquals(-1, Corner.parseSize("big"));
        assertEquals(Boolean.TRUE, Corner.parseSound("ON"));
        assertEquals(Boolean.FALSE, Corner.parseSound("0"));
        assertNull(Corner.parseSound("loud"));
    }

    @Test
    public void requestsSurviveARestart() {
        Corner.Request r = new Corner.Request("Nursery", "rtsp://u:p@192.168.1.41/s", 0, Corner.Place.TOP_LEFT, 40, true);
        Corner.Request back = Corner.Request.fromJson(r.toJson());
        assertEquals("Nursery", back.name);
        assertEquals("rtsp://u:p@192.168.1.41/s", back.url);
        assertEquals(Corner.Place.TOP_LEFT, back.place);
        assertEquals(40, back.size);
        assertEquals(Boolean.TRUE, back.sound);
        Corner.Request plain = Corner.Request.fromJson(r.plain().toJson());
        assertNull(plain.place);
        assertEquals(0, plain.size);
        assertNull(plain.sound);
        assertEquals(10, Corner.Request.fromJson(new Corner.Request(null, null, 10, null, 0, null).toJson()).cycle);
        assertNull("nothing to play", Corner.Request.fromJson("{\"url\":\"ftp://x\"}"));
        assertNull(Corner.Request.fromJson("not json"));
    }

    @Test
    public void cycleSteps() {
        assertEquals(20, Corner.nextCycle(10, 1));
        assertEquals(5, Corner.nextCycle(10, -1));
        assertEquals(5, Corner.nextCycle(5, -1));
        assertEquals(60, Corner.nextCycle(60, 1));
        assertEquals(10, Corner.nextCycle(7, 1));
    }

    @Test
    public void picturesAreToldFromVideo() {
        assertTrue(Corner.surelyVideo("rtsp://192.168.1.40/s"));
        assertTrue(Corner.surelyVideo("http://h/live/news.m3u8?token=1"));
        assertFalse(Corner.surelyVideo("http://192.168.1.40/snapshot.jpg"));
        assertFalse(Corner.surelyVideo("http://192.168.1.40/video.cgi"));
        assertEquals(Corner.Kind.STILL, Corner.kind("image/jpeg"));
        assertEquals(Corner.Kind.MJPEG, Corner.kind("multipart/x-mixed-replace; boundary=frame"));
        assertEquals(Corner.Kind.VIDEO, Corner.kind("application/vnd.apple.mpegurl"));
        assertEquals(Corner.Kind.VIDEO, Corner.kind(null));
    }

    @Test
    public void mjpegFramesAreFound() {
        byte[] a = {(byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, (byte) 0xD9};
        byte[] b = {(byte) 0xFF, (byte) 0xD8, 3, (byte) 0xFF, 0, (byte) 0xFF, (byte) 0xD9};
        byte[] head = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes();
        java.io.ByteArrayOutputStream s = new java.io.ByteArrayOutputStream();
        s.write(head, 0, head.length);
        s.write(a, 0, a.length);
        s.write(head, 0, head.length);
        s.write(b, 0, b.length);
        byte[] all = s.toByteArray();
        Corner.Jpegs j = new Corner.Jpegs();
        // Split mid-picture: the second picture arrives in two reads.
        int cut = all.length - 3;
        List<byte[]> first = j.feed(all, cut);
        List<byte[]> rest = j.feed(Arrays.copyOfRange(all, cut, all.length), all.length - cut);
        assertEquals(1, first.size());
        assertArrayEquals(a, first.get(0));
        assertEquals(1, rest.size());
        assertArrayEquals(b, rest.get(0));
    }

    @Test
    public void passwordsGoInAHeaderNotTheAddress() {
        assertEquals("admin:p@ss word", Corner.userInfo("http://admin:p%40ss%20word@192.168.1.40/snap.jpg"));
        assertEquals("http://192.168.1.40/snap.jpg", Corner.withoutUserInfo("http://admin:p%40ss%20word@192.168.1.40/snap.jpg"));
        assertNull(Corner.userInfo("http://192.168.1.40/snap.jpg"));
        assertEquals("http://192.168.1.40/a@b", Corner.withoutUserInfo("http://192.168.1.40/a@b"));
    }

    // Doorbell

    private static final List<Corner.Source> SOURCES = Arrays.asList(
            new Corner.Source("Front door", "http://192.168.1.40/snap.jpg", true),
            new Corner.Source("HDMI2", Corner.inputUrl("hw/HDMI2"), false));

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void aValidDoorbellRing() {
        Corner.Ring r = Corner.Ring.parse(params("event", "ring", "device", "Front Door", "camera", "front door",
                "seconds", "45", "corner", "top-right", "size", "33", "sound", "false"), new Corner.Bell(), SOURCES);
        assertNull(r.error);
        assertEquals(Corner.Event.RING, r.event);
        assertEquals("Front Door", r.device);
        assertEquals("Front door", r.source.name);
        assertEquals("camera", r.sourceKind());
        assertEquals(45, r.seconds);
        assertEquals(Corner.Place.TOP_RIGHT, r.place);
        assertFalse(r.chime);
        Corner.Request q = r.request(1234);
        assertEquals("Front Door", q.bell);
        assertEquals("http://192.168.1.40/snap.jpg", q.url);
        assertFalse(q.card());
        assertEquals(1234, q.at);
    }

    @Test
    public void aDoorbellFollowsItsSettings() {
        Corner.Bell b = new Corner.Bell();
        b.camera = "Front door";
        b.place = Corner.Place.BOTTOM_LEFT;
        b.seconds = 15;
        b.chime = true;
        Corner.Ring r = Corner.Ring.parse(params(), b, SOURCES);
        assertNull(r.error);
        assertEquals("no event is a ring", Corner.Event.RING, r.event);
        assertEquals("Front door", r.device);
        assertEquals(Corner.Place.BOTTOM_LEFT, r.place);
        assertEquals(15, r.seconds);
        assertTrue(r.chime);
        assertNull("the camera's sound is off unless asked", r.request(1).sound);
        b.video = false;
        assertEquals("notification only", "notification", Corner.Ring.parse(params(), b, SOURCES).sourceKind());
        assertEquals("an input works as the camera", "HDMI2",
                Corner.Ring.parse(params("camera", "hdmi2"), new Corner.Bell(), SOURCES).source.name);
    }

    @Test
    public void aMissingCameraStillShowsTheBanner() {
        Corner.Ring r = Corner.Ring.parse(params("camera", "Garage"), new Corner.Bell(), SOURCES);
        assertNull(r.error);
        assertNull(r.source);
        assertEquals("notification", r.sourceKind());
        assertTrue(r.warning.contains("Garage"));
        assertEquals("Garage", r.device);
        Corner.Request q = r.request(1);
        assertTrue(q.card());
        Corner.Request back = Corner.Request.fromJson(q.toJson());
        assertTrue("a banner alone survives its trip through the service", back.card());
        assertEquals("Garage", back.bell);
        assertEquals("doorbell with no camera at all", "Doorbell",
                Corner.Ring.parse(params(), new Corner.Bell(), SOURCES).device);
    }

    @Test
    public void malformedDoorbellRequests() {
        Corner.Bell b = new Corner.Bell();
        assertTrue(Corner.Ring.parse(params("event", "explode"), b, SOURCES).error.startsWith("event"));
        assertTrue(Corner.Ring.parse(params("seconds", "soon"), b, SOURCES).error.startsWith("seconds"));
        assertTrue(Corner.Ring.parse(params("seconds", "2"), b, SOURCES).error.startsWith("seconds"));
        assertTrue(Corner.Ring.parse(params("seconds", "99999"), b, SOURCES).error.startsWith("seconds"));
        assertTrue(Corner.Ring.parse(params("corner", "middle"), b, SOURCES).error.startsWith("corner"));
        assertTrue(Corner.Ring.parse(params("size", "90"), b, SOURCES).error.startsWith("size"));
        assertTrue(Corner.Ring.parse(params("sound", "loud"), b, SOURCES).error.startsWith("sound"));
        assertTrue(Corner.Ring.parse(params("video", "maybe"), b, SOURCES).error.startsWith("video"));
        assertTrue(Corner.Ring.parse(params("cooldown", "-1"), b, SOURCES).error.startsWith("cooldown"));
        assertTrue(Corner.Ring.parse(params("url", "file:///sdcard/x.jpg"), b, SOURCES).error.startsWith("url"));
        assertTrue(Corner.Ring.parse(params("device", new String(new char[300]).replace('\0', 'x')), b, SOURCES)
                .error.startsWith("device"));
    }

    @Test
    public void aDoorbellUrlMayNotPointAtTheTvItself() {
        assertTrue(Corner.selfOrLinkLocal("http://127.0.0.1:8765/stop"));
        assertTrue(Corner.selfOrLinkLocal("http://localhost:5555/"));
        assertTrue(Corner.selfOrLinkLocal("http://[::1]/x"));
        assertTrue(Corner.selfOrLinkLocal("http://169.254.169.254/latest"));
        assertTrue(Corner.selfOrLinkLocal("http://0.0.0.0/"));
        assertTrue(Corner.selfOrLinkLocal("not a url"));
        assertFalse(Corner.selfOrLinkLocal("http://192.168.1.10:8123/api/camera_proxy/camera.front_door?token=abc"));
        assertFalse(Corner.selfOrLinkLocal("rtsp://u:p@cam.lan:554/s"));
        Corner.Ring r = Corner.Ring.parse(params("url", "http://127.0.0.1:8765/stop"), new Corner.Bell(), SOURCES);
        assertTrue(r.error.startsWith("url"));
        Corner.Ring ok = Corner.Ring.parse(params("url", "http://192.168.1.10:8123/api/camera_proxy/camera.front_door?token=abc",
                "device", "Front Door"), new Corner.Bell(), SOURCES);
        assertNull(ok.error);
        assertEquals("url", ok.sourceKind());
        assertEquals("the address's host, never its token, is its name", "http://192.168.1.10:8123", ok.source.name);
    }

    @Test
    public void doorbellNamesAreCleaned() {
        assertEquals("Front Door", Corner.label("  Front\n\tDoor \u0007", "x"));
        assertEquals("x", Corner.label("   ", "x"));
        assertEquals("x", Corner.label(null, "x"));
        String longName = Corner.label("A very long doorbell name that goes on and on and on", "x");
        assertTrue(longName.length() <= Corner.MAX_LABEL);
        assertTrue(longName.startsWith("A very long doorbell name"));
        assertTrue(longName.endsWith("…"));
    }

    @Test
    public void doorbellEvents() {
        assertEquals(Corner.Event.RING, Corner.parseEvent("ding"));
        assertEquals(Corner.Event.RING, Corner.parseEvent(" Ring "));
        assertEquals(Corner.Event.RING, Corner.parseEvent(null));
        assertEquals(Corner.Event.MOTION, Corner.parseEvent("motion"));
        assertEquals(Corner.Event.TEST, Corner.parseEvent("test"));
        assertEquals(Corner.Event.DISMISS, Corner.parseEvent("clear"));
        assertNull(Corner.parseEvent("knock"));
    }

    @Test
    public void repeatedRingsAndMotionCooldown() {
        Corner.Gate g = new Corner.Gate();
        long t = 1_000_000;
        assertNull("first ring shows", g.check(Corner.Event.RING, t, 45, 60, true));
        assertTrue("same press sent twice: one popup", g.check(Corner.Event.RING, t + 1000, 45, 60, true) != null);
        assertNull("rung again later: shown again, fresh timer", g.check(Corner.Event.RING, t + 6000, 45, 60, true));
        assertTrue("no motion over a ring's popup", g.check(Corner.Event.MOTION, t + 20_000, 45, 60, true) != null);
        assertNull("the ring's popup is over: motion shows", g.check(Corner.Event.MOTION, t + 52_000, 45, 60, true));
        assertTrue("motion cooldown", g.check(Corner.Event.MOTION, t + 80_000, 45, 60, true) != null);
        assertNull("cooldown over", g.check(Corner.Event.MOTION, t + 113_000, 45, 60, true));
        assertTrue("motion off: ring only", g.check(Corner.Event.MOTION, t + 999_000, 45, 60, false) != null);
        assertNull("a test always shows", g.check(Corner.Event.TEST, t + 113_001, 45, 60, true));
        assertNull("a ring right after motion shows", g.check(Corner.Event.RING, t + 113_002, 45, 60, true));
        g.dismissed();
        assertNull("dismissed: motion may show again", g.check(Corner.Event.MOTION, t + 200_000, 45, 0, true));
    }

    @Test
    public void theCornerComesBackAfterADoorbell() {
        Corner.Request nursery = new Corner.Request("Nursery", "rtsp://192.168.1.41/s", 0, null, 0, null);
        Corner.Request bell = Corner.Ring.parse(params("camera", "Front door"), new Corner.Bell(), SOURCES).request(1);
        Corner.Slot s = new Corner.Slot();
        s.open(nursery, false, true);
        s.open(bell, true, true);
        assertTrue(s.isShort());
        assertEquals(bell, s.now);
        assertEquals("timer over: the nursery again", nursery, s.end());
        assertEquals(nursery, s.now);
        assertFalse(s.isShort());
    }

    @Test
    public void competingShortOnesKeepTheFirstWayBack() {
        Corner.Request nursery = new Corner.Request("Nursery", "rtsp://192.168.1.41/s", 0, null, 0, null);
        Corner.Request shortPlay = new Corner.Request("Garage", "rtsp://192.168.1.42/s", 0, null, 0, null);
        Corner.Request bell = Corner.Ring.parse(params(), new Corner.Bell(), SOURCES).request(1);
        Corner.Slot s = new Corner.Slot();
        s.open(nursery, false, true);
        s.open(shortPlay, true, true);
        s.open(bell, true, true);
        s.open(bell, true, true);
        assertEquals("never back to the garage or the first ring", nursery, s.end());
        assertNull("nothing short now: nothing more to end to", s.end());
    }

    @Test
    public void aDoorbellOverAnEmptyCornerCloses() {
        Corner.Slot s = new Corner.Slot();
        s.open(Corner.Ring.parse(params(), new Corner.Bell(), SOURCES).request(1), true, true);
        assertNull(s.end());
    }

    @Test
    public void restoreOffClosesTheCorner() {
        Corner.Slot s = new Corner.Slot();
        s.open(new Corner.Request("Nursery", "rtsp://192.168.1.41/s", 0, null, 0, null), false, true);
        s.open(Corner.Ring.parse(params(), new Corner.Bell(), SOURCES).request(1), true, false);
        assertNull(s.end());
    }

    @Test
    public void somethingNewReplacesAShortOneForGood() {
        Corner.Slot s = new Corner.Slot();
        s.open(new Corner.Request("Nursery", "rtsp://192.168.1.41/s", 0, null, 0, null), false, true);
        s.open(Corner.Ring.parse(params(), new Corner.Bell(), SOURCES).request(1), true, true);
        Corner.Request garage = new Corner.Request("Garage", "rtsp://192.168.1.42/s", 0, null, 0, null);
        s.open(garage, false, true);
        assertFalse(s.isShort());
        assertNull("not short: the timer has nothing to end", s.end());
    }

    @Test
    public void aCameraThatFailsLeavesTheBannerAndItsWayBack() {
        Corner.Request nursery = new Corner.Request("Nursery", "rtsp://192.168.1.41/s", 0, null, 0, null);
        Corner.Request bell = Corner.Ring.parse(params("camera", "Front door", "device", "Front Door"),
                new Corner.Bell(), SOURCES).request(1);
        Corner.Slot s = new Corner.Slot();
        s.open(nursery, false, true);
        s.open(bell, true, true);
        // The snapshot address stopped answering (an expired token): the banner alone.
        s.replace(s.now.cardOnly());
        assertTrue(s.now.card());
        assertEquals("Front Door", s.now.bell);
        assertTrue(s.isShort());
        assertEquals(nursery, s.end());
    }

    @Test
    public void doorbellPopupsKeepTheirOwnPlace() {
        Corner.Request bell = Corner.Ring.parse(params("corner", "top-left"), new Corner.Bell(), SOURCES).request(1);
        assertEquals(Corner.Place.TOP_LEFT, bell.plain().place);
        Corner.Request back = Corner.Request.fromJson(bell.toJson());
        assertEquals(Corner.Place.TOP_LEFT, back.place);
        assertEquals(1, back.at);
        Corner.Request motion = Corner.Ring.parse(params("event", "motion"), new Corner.Bell(), SOURCES).request(1);
        assertTrue(Corner.Request.fromJson(motion.toJson()).motion);
    }

    @Test
    public void doorbellSettingsPersist() {
        Corner.Bell b = new Corner.Bell();
        b.on = false;
        b.camera = "Front door";
        b.place = Corner.Place.BOTTOM_LEFT;
        b.size = 40;
        b.seconds = 120;
        b.chime = true;
        b.motion = true;
        b.cooldown = 300;
        b.video = false;
        b.restore = false;
        Corner.Bell back = Corner.Bell.fromJson(b.toJson());
        assertFalse(back.on);
        assertEquals("Front door", back.camera);
        assertEquals(Corner.Place.BOTTOM_LEFT, back.place);
        assertEquals(40, back.size);
        assertEquals(120, back.seconds);
        assertTrue(back.chime);
        assertTrue(back.motion);
        assertEquals(300, back.cooldown);
        assertFalse(back.video);
        assertFalse(back.restore);
        Corner.Bell d = Corner.Bell.fromJson("");
        assertTrue("on by default (network control is what's off by default)", d.on);
        assertEquals(Corner.Place.TOP_RIGHT, d.place);
        assertEquals(45, d.seconds);
        assertFalse("silent by default", d.chime);
        assertFalse(d.motion);
        assertTrue(d.restore);
        Corner.Bell bad = Corner.Bell.fromJson("{\"seconds\": 7, \"size\": 90, \"corner\": \"middle\", \"cooldown\": 5}");
        assertEquals(45, bad.seconds);
        assertEquals(33, bad.size);
        assertEquals(Corner.Place.TOP_RIGHT, bad.place);
        assertEquals(60, bad.cooldown);
        assertEquals(45, Corner.Bell.fromJson("not json").seconds);
    }

    @Test
    public void doorbellSteps() {
        assertEquals(60, Corner.step(Corner.BELL_SECONDS, 45, 1));
        assertEquals(120, Corner.step(Corner.BELL_SECONDS, 120, 1));
        assertEquals(10, Corner.next(Corner.BELL_SECONDS, 120));
        assertEquals(0, Corner.step(Corner.COOLDOWNS, 30, -1));
    }

    @Test
    public void sizesCycle() {
        assertEquals(33, Corner.nextSize(25));
        assertEquals(40, Corner.nextSize(33));
        assertEquals(25, Corner.nextSize(40));
        assertEquals(33, Corner.nextSize(99));
    }
}
