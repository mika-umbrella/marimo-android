package moe.umbrella.marimo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetching the library from a desktop running {@code marimo-sync serve}.
 *
 * <p>The parts that decide things -- which address we are allowed to talk to, and
 * which albums are missing -- are plain Java with no Android types, so
 * {@link SyncClientTest} can check them on the JVM. Same reason {@link CoverName}
 * and {@link WaveSidecar} are separate from the scanner: a rule with no test is a
 * rule nothing checks.
 *
 * <p>The manifest grants cleartext for the whole app (a network-security-config
 * matches domains, not address ranges, so "the LAN only" cannot be said there).
 * {@link #allowedAddress} is therefore where that rule actually lives.
 */
public final class SyncClient {

    public static final int DEFAULT_PORT = 8422;
    private static final String TOKEN_HEADER = "X-Marimo-Token";

    private SyncClient() { }

    /* ============================ the address rule ============================ */

    /** https anywhere; http only for loopback, the private ranges, and .local.
     *
     *  The manifest permission is app-wide, so this is the narrowing. Anything
     *  public is refused outright rather than trusted to be kind. */
    public static boolean allowedAddress(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("https://")) return true;
        if (!u.startsWith("http://")) return false;
        String host = hostOf(u);
        if (host.isEmpty()) return false;
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.equals("::1") || host.equals("[::1]")) return true;
        if (host.endsWith(".local")) return true;
        int[] v4 = ipv4(host);
        if (v4 == null) return false;            /* a public name: no cleartext */
        int a = v4[0], b = v4[1];
        if (a == 127) return true;               /* loopback           */
        if (a == 10) return true;                /* 10/8               */
        if (a == 192 && b == 168) return true;   /* 192.168/16         */
        if (a == 172 && b >= 16 && b <= 31) return true;  /* 172.16/12  */
        if (a == 169 && b == 254) return true;   /* link-local         */
        return false;
    }

    /** "192.168.0.5", "192.168.0.5:8422", "http://x/" -> "http://host:port". */
    public static String normalise(String typed) {
        String s = typed == null ? "" : typed.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        /* trim trailing slashes, but never past the host: "http://" is not "http:" */
        int afterScheme = s.indexOf("://") + 3;
        int cut = s.length();
        while (cut > afterScheme && s.charAt(cut - 1) == '/') cut--;
        s = s.substring(0, cut);
        String host = hostOf(s.toLowerCase(Locale.ROOT));
        if (host.isEmpty()) return "";
        if (portOf(s) > 0) return s;
        return s + ":" + DEFAULT_PORT;
    }

    static String hostOf(String url) {
        int i = url.indexOf("://");
        String rest = i < 0 ? url : url.substring(i + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (rest.startsWith("[")) {                  /* [v6]:port */
            int close = rest.indexOf(']');
            return close > 0 ? rest.substring(0, close + 1) : "";
        }
        int colon = rest.lastIndexOf(':');
        return colon >= 0 ? rest.substring(0, colon) : rest;
    }

    static int portOf(String url) {
        int i = url.indexOf("://");
        String rest = i < 0 ? url : url.substring(i + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0 || close + 1 >= rest.length() || rest.charAt(close + 1) != ':') return -1;
            return parsePort(rest.substring(close + 2));
        }
        int colon = rest.lastIndexOf(':');
        return colon < 0 ? -1 : parsePort(rest.substring(colon + 1));
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int[] ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int c = 0; c < p.length(); c++) if (!Character.isDigit(p.charAt(c))) return null;
            out[i] = Integer.parseInt(p);
            if (out[i] > 255) return null;
        }
        return out;
    }

    /* =========================== what is missing =========================== */

    /** Album names whose files aren't all present on the device, in index order.
     *
     *  A file counts as present only when a file of that name is there at the same
     *  length; anything else means fetching the album again. */
    public static List<String> missingAlbums(List<String> libraryOrder,
                                             Map<String, Map<String, Long>> library,
                                             Map<String, Map<String, Long>> device) {
        List<String> out = new ArrayList<>();
        for (String album : libraryOrder) {
            Map<String, Long> want = library.get(album);
            if (want == null || want.isEmpty()) continue;
            Map<String, Long> have = device.get(album);
            if (have == null) {
                out.add(album);
                continue;
            }
            for (Map.Entry<String, Long> f : want.entrySet()) {
                Long got = have.get(f.getKey());
                if (got == null || !got.equals(f.getValue())) {
                    out.add(album);
                    break;
                }
            }
        }
        return out;
    }

    /** File names in one album that the device needs, in the library's order.
     *
     *  Presence means a file of that name at that exact length; anything else is
     *  fetched. Per-file rather than per-album so a missing cover doesn't drag a
     *  whole album's audio across the network. */
    public static List<String> missingFiles(Map<String, Long> want, Map<String, Long> have) {
        List<String> out = new ArrayList<>();
        if (want == null) return out;
        for (Map.Entry<String, Long> f : want.entrySet()) {
            Long got = have == null ? null : have.get(f.getKey());
            if (got == null || !got.equals(f.getValue())) out.add(f.getKey());
        }
        return out;
    }

    /* ============================== the fetching ============================== */

    /** One GET, returning a connection positioned for reading (or throwing). */
    public static HttpURLConnection open(String url, String token, String range) throws IOException {
        if (!allowedAddress(url)) {
            throw new IOException("refusing to talk to " + url
                    + " -- cleartext is only allowed on the local network");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "*/*");
        if (token != null && !token.isEmpty()) c.setRequestProperty(TOKEN_HEADER, token);
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    public static String get(String url, String token) throws IOException {
        HttpURLConnection c = open(url, token, null);
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                // Worth its own message: the server is plainly reachable, and the
                // only thing wrong is the token -- which is otherwise reported as a
                // bare "couldn't reach it" and sends you looking at the network.
                throw new IOException("the token doesn't match the one marimo-sync serve is "
                        + "using -- check it in the window's header");
            }
            if (code != 200) throw new IOException("HTTP " + code + " from " + url);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Where the bytes go. SAF-backed in the app, a File in a test. */
    public interface Sink {
        OutputStream open(boolean append) throws IOException;
    }

    /** A sink that writes to a plain file. */
    public static Sink fileSink(File f) {
        return append -> new FileOutputStream(f, append);
    }

    /** Stream one library file into {@code sink}, resuming when the sink already
     *  holds a prefix of it. Returns how many bytes it now holds.
     *
     *  Resuming matters: a phone's wifi drops, and re-fetching a file because the
     *  last attempt died a megabyte from the end is the waste this project exists
     *  to avoid.
     *
     *  Files rather than album tars, deliberately: the tar endpoint is written in
     *  PAX format, which encodes long and non-ASCII names in extended headers, and
     *  a hand-rolled extractor would quietly mangle half a Japanese library. One
     *  request per file has no such subtleties. */
    public static long download(String base, String token, String album, String name,
                                long have, Sink sink, Progress progress) throws IOException {
        String url = base + "/api/file/" + uriEncode(album) + "/" + uriEncode(name);
        HttpURLConnection c = open(url, token, have > 0 ? "bytes=" + have + "-" : null);
        int code = c.getResponseCode();
        if (code == 416) {                       /* the sink is already complete */
            c.disconnect();
            return have;
        }
        if (code != 200 && code != 206) {
            c.disconnect();
            throw new IOException("HTTP " + code + " fetching " + name);
        }
        boolean appending = code == 206 && have > 0;
        long total = c.getContentLengthLong() + (appending ? have : 0);
        long done = appending ? have : 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = sink.open(appending)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.at(done, total);
            }
        } finally {
            c.disconnect();
        }
        return done;
    }

    static String uriEncode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append((char) c);
            } else {
                b.append('%').append(String.format("%02X", c));
            }
        }
        return b.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.append(new String(buf, 0, n, "UTF-8"));
        return b.toString();
    }

    /** Progress callback, so the screen can show something honest. */
    public interface Progress {
        void at(long done, long total);
    }
}
