package io.github.maxhayim.cornerplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Network control: Home Assistant, a doorbell or any device on the home network opens a camera or input in the
 * corner with an HTTP request, or closes it. Off unless switched on; every request needs the token shown on the
 * TV. Runs only while it's on, and comes back after a restart.
 *
 * <pre>
 * POST /play    name=Front door [seconds=60]   a camera or input by name
 *               url=rtsp://... [title=Door]    or any stream address
 * POST /stop
 * GET  /status
 * </pre>
 */
public class RemoteService extends Service {
    static final int PORT = 8765;
    private static final String CHANNEL = "remote";
    /** Request line and headers, then the body: anything larger is refused. */
    private static final int MAX_HEAD = 8192, MAX_BODY = 4096;
    private static final int TIMEOUT_MS = 5000;

    /** Why the listener isn't listening (the port is taken, say), for the app's screen; null when it is. */
    static volatile String problem;

    private volatile ServerSocket server;
    private volatile boolean stopped;

    /** Starts or stops the listener to match the setting. */
    static void sync(Context c) {
        Intent i = new Intent(c, RemoteService.class);
        if (Config.remote(c)) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.remote_channel_name),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.remote_notification_text))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(2, n);
        new Thread(this::serve, "remote").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Config.remote(this)) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
        problem = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** One request at a time: a home automation sends a few a day, and a guesser gets no parallel tries. */
    private void serve() {
        try (ServerSocket s = new ServerSocket(PORT)) {
            server = s;
            // Switched off while the port was opening: let it go now.
            if (stopped) return;
            problem = null;
            while (!s.isClosed()) {
                try (Socket c = s.accept()) {
                    c.setSoTimeout(TIMEOUT_MS);
                    handle(c);
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(CornerService.TAG, "network control request failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problem = "Can't listen on port " + PORT + ": " + e.getMessage();
            Log.w(CornerService.TAG, problem);
        }
    }

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        String head = readHead(in);
        OutputStream out = c.getOutputStream();
        if (head == null) {
            reply(out, 400, error("Bad request."));
            return;
        }
        String[] lines = head.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) {
            reply(out, 400, error("Bad request."));
            return;
        }
        String method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        String auth = null;
        int length = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) continue;
            String k = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = lines[i].substring(colon + 1).trim();
            if (k.equals("authorization")) auth = v;
            else if (k.equals("content-length")) length = parseLength(v);
        }
        if (length > MAX_BODY) {
            reply(out, 413, error("Too large."));
            return;
        }
        if (!Corner.authorized(Config.token(this), auth)) {
            reply(out, 401, error("Needs Authorization: Bearer and the token shown in Corner Player."));
            return;
        }
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        Map<String, String> params = Corner.query(q < 0 ? "" : target.substring(q + 1));
        params.putAll(body(in, length));

        if (path.equals("/status") && method.equals("GET")) {
            reply(out, 200, "{\"playing\":" + (CornerService.playing == null ? "null" : JSONObject.quote(CornerService.playing)) + "}");
        } else if (path.equals("/stop") && method.equals("POST")) {
            CornerService.stop(this);
            reply(out, 200, "{\"ok\":true}");
        } else if (path.equals("/play") && method.equals("POST")) {
            play(out, params);
        } else if (path.equals("/status") || path.equals("/stop") || path.equals("/play")) {
            reply(out, 405, error("Use GET /status, POST /play or POST /stop."));
        } else {
            reply(out, 404, error("Use GET /status, POST /play or POST /stop."));
        }
    }

    private void play(OutputStream out, Map<String, String> params) throws IOException {
        Corner.Source s;
        String url = params.get("url");
        if (url != null && !url.trim().isEmpty()) {
            if (!Corner.validUrl(url.trim())) {
                reply(out, 400, error("url must be an rtsp://, http:// or https:// address."));
                return;
            }
            String title = params.get("title");
            s = new Corner.Source(title == null || title.trim().isEmpty() ? Corner.shown(url.trim()) : title.trim(),
                    url.trim(), false);
        } else {
            String name = params.get("name");
            s = Corner.find(Config.cameras(this), name);
            if (s == null) s = Corner.find(CornerService.inputs(this), name);
            if (s == null) {
                reply(out, 404, error(name == null ? "Give a name (a camera or input) or a url." : "No camera or input named " + name + "."));
                return;
            }
        }
        if (!CornerService.canDraw(this)) {
            reply(out, 409, error("Display over other apps isn't allowed for Corner Player."));
            return;
        }
        try {
            CornerService.play(this, s.name, s.url, Corner.seconds(params.get("seconds")));
        } catch (RuntimeException e) {
            // Android may refuse to start the window's service from the background.
            Log.w(CornerService.TAG, "network control can't open the window: " + e);
            reply(out, 409, error("This TV won't let Corner Player open the window from the background."));
            return;
        }
        reply(out, 200, "{\"ok\":true,\"playing\":" + JSONObject.quote(s.name) + "}");
    }

    /** A Content-Length; anything unreadable counts as too large. */
    private static int parseLength(String v) {
        try {
            int n = Integer.parseInt(v);
            return n < 0 ? MAX_BODY + 1 : n;
        } catch (NumberFormatException e) {
            return MAX_BODY + 1;
        }
    }

    /** Request line and headers, up to the blank line; null if too large or cut off. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int matched = 0;
        while (b.size() < MAX_HEAD) {
            int ch = in.read();
            if (ch < 0) return null;
            b.write(ch);
            matched = (ch == "\r\n\r\n".charAt(matched)) ? matched + 1 : (ch == '\r' ? 1 : 0);
            if (matched == 4) return b.toString(StandardCharsets.UTF_8.name()).trim();
        }
        return null;
    }

    /** A form body (name=...&seconds=...) or a JSON one ({"name": ..., "seconds": 60}). */
    private static Map<String, String> body(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int got = 0;
        for (int n; got < length && (n = in.read(buf, got, length - got)) > 0; ) got += n;
        String text = new String(buf, 0, got, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("{")) return Corner.query(text);
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(text);
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String k = keys.next();
                out.put(k, o.optString(k));
            }
        } catch (Exception ignored) {
            // Not JSON: no parameters from the body.
        }
        return out;
    }

    private static String error(String message) {
        return "{\"error\":" + JSONObject.quote(message) + "}";
    }

    private static void reply(OutputStream out, int code, String json) throws IOException {
        byte[] body = (json + "\n").getBytes(StandardCharsets.UTF_8);
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 401: status = "Unauthorized"; break;
            case 404: status = "Not Found"; break;
            case 405: status = "Method Not Allowed"; break;
            case 409: status = "Conflict"; break;
            case 413: status = "Payload Too Large"; break;
            default: status = "Error";
        }
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
