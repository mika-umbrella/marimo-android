package moe.umbrella.marimo;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.security.MessageDigest;

/** Last.fm + ListenBrainz scrobbling — same rules as the desktop player:
 *  submit when a track plays >=50% of its duration OR >=4 minutes (and
 *  is longer than 30s), once per item; now-playing on track start.
 *  Plain java HTTP (no curl dependency needed on android). */
public class Scrobbler {

    private static final String LF_API = "https://ws.audioscrobbler.com/2.0/";
    private static final String LB_API = "https://api.listenbrainz.org/1/submit-listens";

    /* the app's own last.fm API credentials (nova's, baked in) — the user
     * never needs to enter these; the interactive login fetches the session
     * key for them. */
    private static volatile String lfKey = "78ce5d8d089235d368ce38e672b3a335";
    private static volatile String lfSecret = "9ce63846c15e759f7161a6436c698442";
    private static volatile String lfSession = "";
    private static volatile String lfUser = "";
    private static volatile String lbToken = "";

    /** set from the settings dialog */
    public static void configure(String lfKeyV, String lfSecretV, String lfSessionV,
                                 String lfUserV, String lbTokenV) {
        /* only override with non-empty values — keeps the baked-in app
         * api key/secret if prefs come back blank on a fresh install */
        if (lfKeyV != null && !lfKeyV.isEmpty()) lfKey = lfKeyV;
        if (lfSecretV != null && !lfSecretV.isEmpty()) lfSecret = lfSecretV;
        if (lfSessionV != null && !lfSessionV.isEmpty()) lfSession = lfSessionV;
        if (lfUserV != null && !lfUserV.isEmpty()) lfUser = lfUserV;
        if (lbTokenV != null && !lbTokenV.isEmpty()) lbToken = lbTokenV;
        saveToPrefs();
    }

    public static boolean hasLf() { return !lfKey.isEmpty() && !lfSession.isEmpty(); }
    public static boolean hasLb() { return !lbToken.isEmpty(); }

    private static void saveToPrefs() {
        // persisted via MainActivity's SharedPreferences on dialog OK
    }

    public static String[] current() {
        return new String[]{lfKey, lfSecret, lfSession, lfUser, lbToken};
    }

    /** call on track start — reports "now playing" to both services */
    public static void nowPlaying(final Track t) {
        if (t == null) return;
        Thread th = new Thread(() -> {
            if (hasLf()) lfNowPlaying(t);
            if (hasLb()) lbNowPlaying(t);
        });
        th.setDaemon(true);
        th.start();
    }

    /** call when a track ends; returns true if it was scrobblable+submitted */
    public static void scrobble(final Track t, final long playedMs) {
        if (t == null) return;
        final boolean q = qualifies(t, playedMs);
        Thread th = new Thread(() -> {
            if (!q) return;
            if (hasLf()) lfScrobble(t, playedMs);
            if (hasLb()) lbScrobble(t, playedMs);
        });
        th.setDaemon(true);
        th.start();
    }

    /** desktop rule: >=50% of duration or >=4 minutes, track longer than 30s */
    private static boolean qualifies(Track t, long playedMs) {
        long fourMin = 4 * 60 * 1000;
        if (t.durationMs > 0) {
            if (t.durationMs <= 30000) return false;     /* too short */
            long half = t.durationMs / 2;
            return playedMs >= half || playedMs >= fourMin;
        }
        /* duration unknown (mp3 tag read gives none) — require a long enough
         * play so we don't scrobble things we can't verify */
        return playedMs >= fourMin;
    }

    /* ---------------- last.fm ---------------- */

    private static void lfNowPlaying(Track t) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "track.updateNowPlaying");
        p.put("artist", t.artist);
        p.put("track", t.title.isEmpty() ? t.name : t.title);
        p.put("album", t.album);
        p.put("duration", String.valueOf(t.durationMs / 1000));
        p.put("api_key", lfKey);
        p.put("sk", lfSession);
        post(LF_API, p, true);
    }

    private static void lfScrobble(Track t, long playedMs) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("method", "track.scrobble");
        p.put("artist", t.artist);
        p.put("track", t.title.isEmpty() ? t.name : t.title);
        p.put("album", t.album);
        p.put("duration", String.valueOf(t.durationMs / 1000));
        p.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        p.put("api_key", lfKey);
        p.put("sk", lfSession);
        post(LF_API, p, true);
    }

    /* ---------------- last.fm interactive login ---------------- */

    /** step 1: request a fresh auth token (no signature needed) */
    public static String requestToken() {
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("method", "auth.gettoken");
            p.put("api_key", lfKey);
            String body = bodyFor(p) + "&format=json";
            HttpURLConnection c = open(LF_API, body);
            int code = c.getResponseCode();
            String resp = readBody(c, code);
            c.disconnect();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"token\":\"([^\"]+)\"").matcher(resp);
            return code == 200 && m.find() ? m.group(1) : null;
        } catch (Exception e) {
            Log.e("marimo", "lf token failed: " + e);
            return null;
        }
    }

    /** step 2: the browser page the user logs in + approves on */
    public static String authUrl(String token) {
        return "https://www.last.fm/api/auth/?api_key=" + lfKey
                + "&token=" + token;
    }

    /** step 3: once approved, exchange the token for a real session key.
     *  sets the configured session/user and returns them (or null). */
    public static String[] finishAuth(String token) {
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("method", "auth.getsession");
            p.put("api_key", lfKey);
            p.put("token", token);
            p.put("api_sig", apiSig(p));
            String body = bodyFor(p) + "&format=json";
            HttpURLConnection c = open(LF_API, body);
            int code = c.getResponseCode();
            String resp = readBody(c, code);
            c.disconnect();
            if (code != 200) return null;
            java.util.regex.Matcher mk = java.util.regex.Pattern
                    .compile("\"key\":\"([^\"]+)\"").matcher(resp);
            java.util.regex.Matcher mn = java.util.regex.Pattern
                    .compile("\"name\":\"([^\"]+)\"").matcher(resp);
            if (!mk.find() || !mn.find()) return null;
            lfSession = mk.group(1);
            lfUser = mn.group(1);
            return new String[]{lfUser, lfSession};
        } catch (Exception e) {
            Log.e("marimo", "lf session failed: " + e);
            return null;
        }
    }

    /* ---------------- listenbrainz ---------------- */

    private static void lbNowPlaying(Track t) {
        String json = "{\"listen_type\":\"playing_now\",\"payload\":[{\"track_metadata\":{"
                + "\"track_name\":" + jq(t.title.isEmpty() ? t.name : t.title)
                + ",\"artist_name\":" + jq(t.artist)
                + ",\"release_name\":" + jq(t.album) + "}}]}";
        postJson(LB_API, json);
    }

    private static void lbScrobble(Track t, long playedMs) {
        String payload = "{\"listen_type\":\"single\",\"payload\":[{\"track_metadata\":{"
                + "\"track_name\":" + jq(t.title.isEmpty() ? t.name : t.title)
                + ",\"artist_name\":" + jq(t.artist)
                + ",\"release_name\":" + jq(t.album)
                + ",\"additional_info\":{\"duration_ms\":" + t.durationMs
                + ",\"media_player\":\"marimo\"}}}]}";
        postJson(LB_API, payload);
    }

    private static String jq(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /* ---------------- http ---------------- */

    /** last.fm write methods require api_sig: md5(sorted "keyvalue" + secret) */
    private static String apiSig(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet())
            s.append(e.getKey()).append(e.getValue());
        s.append(lfSecret);
        return md5Hex(s.toString());
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** url-encoded form body from a param map */
    private static String bodyFor(Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return body.toString();
    }

    /** open a form-encoded POST connection (used by the auth flow) */
    private static HttpURLConnection open(String url, String formBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "marimo-android/1.2.1");
        try (OutputStream os = c.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }
        return c;
    }

    private static void post(String url, Map<String, String> params, boolean lfSigned) {
        try {
            if (lfSigned && !lfSecret.isEmpty())
                params.put("api_sig", apiSig(params));
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("User-Agent", "marimo-android/1.2.1");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = readBody(c, code);
            Log.i("marimo", "lf post -> " + code + " body=" + resp);
            c.disconnect();
        } catch (Exception e) {
            Log.e("marimo", "lf post failed: " + e);
        }
    }

    private static String readBody(HttpURLConnection c, int code) {
        try {
            java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void postJson(String url, String json) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Token " + lbToken);
            c.setRequestProperty("User-Agent", "marimo-android/1.2.1");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            Log.i("marimo", "lb post -> " + code + " body=" + readBody(c, code));
            c.disconnect();
        } catch (Exception e) {
            Log.e("marimo", "lb post failed: " + e);
        }
    }
}
