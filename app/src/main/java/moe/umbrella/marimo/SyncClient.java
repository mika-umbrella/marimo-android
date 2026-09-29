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
import java.util.LinkedHashMap;
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

    /** Package-visible so the diary leg can read a reply through the same decoder. */
    static String readAll(InputStream in) throws IOException {
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

    /* ================================ the diary ================================ */

    /** Where the phone sends its diary and gets the merged one back. A POST, and the
     *  only endpoint that carries a body and answers with files. */
    public static final String DIARY_ENDPOINT = "/api/diary";

    /** One diary file on the wire: its name, its size, and its bytes in base64.
     *
     *  Inline rather than a second fetch: a phone's diary is a few kB, and one round
     *  trip costs less than the 33% base64 adds. Sending the names is what lets the
     *  desktop say which files it merged rather than guessing from a blob. */
    public static String diaryRequestBody(List<DiaryImport.Piece> pieces) {
        StringBuilder b = new StringBuilder("{\"version\":1,\"files\":[");
        for (int i = 0; i < pieces.size(); i++) {
            DiaryImport.Piece p = pieces.get(i);
            if (i > 0) b.append(',');
            b.append("{\"name\":\"").append(escape(p.name))
                    .append("\",\"size\":").append(p.bytes.length)
                    .append(",\"b64\":\"").append(base64(p.bytes)).append("\"}");
        }
        return b.append("]}").toString();
    }

    /** How many diary lines those pieces hold, counting the bytes.
     *
     *  Archives are decompressed to count them: counting 0x0A in gzip bytes gives a
     *  number that is not a line count, and it would be a lie in the log. Anything
     *  unreadable counts as zero rather than as invented lines. */
    public static long diaryLineCount(List<DiaryImport.Piece> pieces) {
        long n = 0;
        for (DiaryImport.Piece p : pieces) {
            try {
                byte[] plain = p.name.endsWith(".gz") ? DiaryImport.gunzip(p.bytes) : p.bytes;
                for (byte x : plain) if (x == '\n') n++;
            } catch (Exception ignored) {
                /* not a line count we can make: contribute nothing */
            }
        }
        return n;
    }

    /** What the desktop's /api/diary said, and the merged files themselves. */
    public static final class DiaryReply {
        public int before = -1, after = -1, added = -1, duplicates = -1;
        public String digest = "";
        public final List<DiaryImport.Piece> files = new ArrayList<>();
        /** names carried that aren't diary files, kept so the log can say what was skipped */
        public final List<String> ignored = new ArrayList<>();
    }

    /** Read a /api/diary reply. Null when the text isn't JSON we can use at all;
     *  a reply that parses but says little is still a reply (every number -1). */
    public static DiaryReply parseDiaryReply(String text) {
        Object root = json(text);
        if (!(root instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) root;
        DiaryReply r = new DiaryReply();
        r.before = (int) num(m.get("before"), -1);
        r.after = (int) num(m.get("after"), -1);
        r.added = (int) num(m.get("added"), -1);
        r.duplicates = (int) num(m.get("duplicates"), -1);
        if (m.get("digest") instanceof String) r.digest = (String) m.get("digest");
        if (m.get("files") instanceof List) {
            for (Object o : (List<?>) m.get("files")) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> f = (Map<?, ?>) o;
                if (!(f.get("name") instanceof String) || !(f.get("b64") instanceof String)) continue;
                String name = (String) f.get("name");
                /* Only the two diary names are imported: a reply that carries anything
                 * else is reported, not fed to the merge, where it would fail as a
                 * corrupt line and refuse the whole import. */
                if (!DiaryImport.isDiaryName(name)) {
                    r.ignored.add(name);
                    continue;
                }
                byte[] bytes = base64Decode((String) f.get("b64"));
                if (bytes == null) r.ignored.add(name + " (unreadable base64)");
                else r.files.add(new DiaryImport.Piece(name, bytes));
            }
        }
        return r;
    }

    /** The one thing worth saying differently per status: whose end is wrong.
     *
     *  A 404 is the version skew the app has to live with -- an older desktop that has
     *  no diary endpoint yet -- and it must not read as "your network is broken". */
    public static String diaryHttpError(int code) {
        return diaryHttpError(code, null);
    }

    /** The same, carrying the desktop's own explanation when it sent one.
     *
     *  401/403 and 404 keep their wording and ignore the body: those two say which END
     *  is wrong, which a body cannot improve on, and a framework trace would only bury
     *  it. For every other code the body is the only place the reason lives -- a 409
     *  says the merge is switched off on that desktop, a 422 says which line the merge
     *  refused -- so it rides along, flattened to one line and cut to a readable
     *  length, because a status bar is not a log. */
    public static String diaryHttpError(int code, String body) {
        if (code == 401 || code == 403) {
            return "the token doesn't match the one marimo-sync serve is using -- "
                    + "check it in the window's header";
        }
        if (code == 404) {
            return "this marimo-sync has no /api/diary endpoint yet -- update it on the "
                    + "computer (the diary rides the same connection as the library)";
        }
        String detail = oneLine(body, 140);
        return detail.isEmpty() ? "HTTP " + code + " from the desktop"
                : "HTTP " + code + " -- " + detail;
    }

    /** A response body reduced to what belongs in a status bar: every run of whitespace
     *  (newlines included) collapses to one space, the ends are trimmed, and the whole
     *  thing is cut to {@code max} characters -- so a stack trace arrives as its first
     *  line rather than taking over the line the user reads. */
    static String oneLine(String body, int max) {
        if (body == null || max <= 0) return "";
        StringBuilder b = new StringBuilder(max + 1);
        boolean pendingSpace = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pendingSpace = b.length() > 0;
                continue;
            }
            if (c < 0x20) continue;                  /* other control characters: noise */
            if (pendingSpace) {
                if (b.length() + 1 >= max) break;
                b.append(' ');
                pendingSpace = false;
            }
            if (b.length() >= max) break;
            b.append(c);
        }
        return b.toString();
    }

    /** One honest line for the sync log:
     *  "diary: sent 47 lines (1 file), got 204 back, imported 157 new".
     *  With no numbers from the desktop the middle clause is left out rather than
     *  invented, and with nothing new the last clause says so. */
    public static String diaryLine(int sentFiles, long sentLines, int desktopLinesAfter,
                                   int importedNew) {
        StringBuilder b = new StringBuilder("diary: sent ").append(sentLines).append(" lines (")
                .append(sentFiles).append(sentFiles == 1 ? " file" : " files").append(')');
        if (desktopLinesAfter >= 0) b.append(", got ").append(desktopLinesAfter).append(" back");
        if (importedNew > 0) b.append(", imported ").append(importedNew).append(" new");
        else b.append(", nothing new");
        return b.toString();
    }

    /** The sync screen's log toggle. Collapsed is the resting state: the status line
     *  already names the file being fetched, so the log is detail you ask for rather
     *  than a wall that grows while you wait. */
    public static String logToggleLabel(boolean logOpen) {
        return logOpen ? "hide log" : "show log";
    }

    /* ------------------------------ json and base64 ------------------------------
     * Hand-rolled on purpose, both of them.
     *
     * org.json is a stub in a JVM unit test, so using it would move the reply's shape
     * out of the tested half. java.util.Base64 is API 26 and this app ships minSdk 24,
     * and android.util.Base64 would drag an Android type in for the same reason. What
     * is left is twenty lines each, and a test that checks my encoder against the
     * JDK's decoder rather than against my own opinion. */

    /** Minimal JSON: Map, List, String, Long, Double, Boolean, null. Null when the
     *  text isn't JSON. Deliberately not a general parser -- our own endpoints only. */
    public static Object json(String text) {
        if (text == null) return null;
        try {
            J p = new J(text);
            p.ws();
            Object v = p.value();
            p.ws();
            return p.atEnd() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class J {
        private final String s;
        private int i;

        J(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        Object value() {
            ws();
            if (atEnd()) throw new IllegalStateException("end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                if (atEnd() || s.charAt(i) != ':') throw new IllegalStateException(":");
                i++;
                m.put(k, value());
                ws();
                if (atEnd()) throw new IllegalStateException("}");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalStateException(",");
            }
        }

        List<Object> array() {
            List<Object> out = new ArrayList<>();
            i++;
            ws();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return out;
            }
            while (true) {
                out.add(value());
                ws();
                if (atEnd()) throw new IllegalStateException("]");
                char c = s.charAt(i++);
                if (c == ']') return out;
                if (c != ',') throw new IllegalStateException(",");
            }
        }

        String string() {
            if (atEnd() || s.charAt(i) != '"') throw new IllegalStateException("string");
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (atEnd()) throw new IllegalStateException("unterminated");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (atEnd()) throw new IllegalStateException("escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalStateException("u");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalStateException("escape");
                }
            }
        }

        Object number() {
            int start = i;
            if (!atEnd() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean fraction = false;
            while (!atEnd()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' || c == 'e' || c == 'E') {
                    fraction = true;
                    i++;
                } else break;
            }
            if (i == start) throw new IllegalStateException("number");
            String n = s.substring(start, i);
            return fraction ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
        }
    }

    private static long num(Object o, long dflt) {
        return o instanceof Number ? ((Number) o).longValue() : dflt;
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    private static final String B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    public static String base64(byte[] data) {
        StringBuilder b = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            boolean two = i + 1 < data.length, three = i + 2 < data.length;
            int n = (data[i] & 0xff) << 16;
            if (two) n |= (data[i + 1] & 0xff) << 8;
            if (three) n |= data[i + 2] & 0xff;
            b.append(B64.charAt((n >> 18) & 63)).append(B64.charAt((n >> 12) & 63));
            b.append(two ? B64.charAt((n >> 6) & 63) : '=');
            b.append(three ? B64.charAt(n & 63) : '=');
        }
        return b.toString();
    }

    /** Null when the text isn't base64 we can read. Padding is optional and whitespace
     *  is ignored, because a reply that came through a proxy may be wrapped. */
    public static byte[] base64Decode(String s) {
        if (s == null) return null;
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '=') break;
            if (B64.indexOf(c) < 0) return null;
            clean.append(c);
        }
        if (clean.length() % 4 == 1) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < clean.length(); i++) {
            acc = ((acc << 6) | B64.indexOf(clean.charAt(i))) & 0xffffff;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
