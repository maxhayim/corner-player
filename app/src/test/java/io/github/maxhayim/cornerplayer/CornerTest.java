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

    @Test
    public void sizesCycle() {
        assertEquals(33, Corner.nextSize(25));
        assertEquals(40, Corner.nextSize(33));
        assertEquals(25, Corner.nextSize(40));
        assertEquals(33, Corner.nextSize(99));
    }
}
