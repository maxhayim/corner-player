package io.github.maxhayim.cornerplayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

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
    public void sizesCycle() {
        assertEquals(33, Corner.nextSize(25));
        assertEquals(40, Corner.nextSize(33));
        assertEquals(25, Corner.nextSize(40));
        assertEquals(33, Corner.nextSize(99));
    }
}
