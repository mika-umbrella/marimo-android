/* tags.c — tiny, dependency-free tag parser.
 * FLAC: fLaC magic, STREAMINFO (duration) + VORBIS_COMMENT (title/artist/
 * album/tracknumber/discnumber). MP3: ID3v2.2/2.3/2.4 frames.
 * Everything else returns -1 and callers fall back to mpv or filename order. */
#define _POSIX_C_SOURCE 200809L   /* fdopen under -std=c11 */
#include "tags.h"
#include "fs.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdint.h>
#include <unistd.h>

static int get_u32be(const unsigned char *p) { return (p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }
static int get_u32le(const unsigned char *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (p[3] << 24); }
static int get_u24be(const unsigned char *p) { return (p[0] << 16) | (p[1] << 8) | p[2]; }

/* parse leading digits of "12" / "12/16" / "A1" -> 12 / 12 / -1 */
static int parse_num(const char *s, int len)
{
    int v = 0, n = 0;
    for (int i = 0; i < len; i++) {
        if (s[i] >= '0' && s[i] <= '9') { v = v * 10 + (s[i] - '0'); n++; }
        else break;
    }
    return n ? v : -1;
}

static void utf16_to_utf8(const unsigned char *u, int len, int be, char *out, int outsz)
{
    int o = 0;
    for (int i = 0; i + 1 < len && o < outsz - 4; i += 2) {
        int c = be ? (u[i] << 8) | u[i + 1] : u[i] | (u[i + 1] << 8);
        if (c == 0) continue;
        if (c >= 0xD800 && c <= 0xDBFF && i + 3 < len) {
            int lo = be ? (u[i + 2] << 8) | u[i + 3] : u[i + 2] | (u[i + 3] << 8);
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                i += 2;
            }
        }
        if (c < 0x80) out[o++] = (char)c;
        else if (c < 0x800) { out[o++] = (char)(0xC0 | (c >> 6)); out[o++] = (char)(0x80 | (c & 63)); }
        else if (c < 0x10000) {
            out[o++] = (char)(0xE0 | (c >> 12)); out[o++] = (char)(0x80 | ((c >> 6) & 63));
            out[o++] = (char)(0x80 | (c & 63));
        } else {
            out[o++] = (char)(0xF0 | (c >> 18)); out[o++] = (char)(0x80 | ((c >> 12) & 63));
            out[o++] = (char)(0x80 | ((c >> 6) & 63)); out[o++] = (char)(0x80 | (c & 63));
        }
    }
    out[o] = 0;
}

static void latin1_to_utf8(const unsigned char *s, int len, char *out, int outsz)
{
    int o = 0;
    for (int i = 0; i < len && o < outsz - 4; i++) {
        int c = s[i];
        if (c < 0x80) out[o++] = (char)c;
        else { out[o++] = (char)(0xC0 | (c >> 6)); out[o++] = (char)(0x80 | (c & 63)); }
    }
    out[o] = 0;
}

static void trim_space(char *s)
{
    size_t l = strlen(s);
    while (l && (s[l - 1] == ' ' || s[l - 1] == '\n' || s[l - 1] == '\r')) s[--l] = 0;
}

/* ID3v2 text frame payload -> utf8 */
static void id3_text(const unsigned char *data, int dlen, char *out, int outsz)
{
    if (dlen < 1) { out[0] = 0; return; }
    int enc = data[0];
    const unsigned char *txt = data + 1;
    int tlen = dlen - 1;
    if (enc == 1) {
        if (tlen >= 2 && txt[0] == 0xFF && txt[1] == 0xFE) { txt += 2; tlen -= 2; utf16_to_utf8(txt, tlen, 0, out, outsz); }
        else if (tlen >= 2 && txt[0] == 0xFE && txt[1] == 0xFF) { txt += 2; tlen -= 2; utf16_to_utf8(txt, tlen, 1, out, outsz); }
        else utf16_to_utf8(txt, tlen, 0, out, outsz);
    } else if (enc == 2) {
        utf16_to_utf8(txt, tlen, 1, out, outsz);
    } else if (enc == 3) {
        if (tlen > outsz - 1) tlen = outsz - 1;
        memcpy(out, txt, tlen);
        out[tlen] = 0;
    } else {
        latin1_to_utf8(txt, tlen, out, outsz);
    }
    trim_space(out);
}

/* ---------------- embedded art ---------------- */

/* FLAC METADATA_BLOCK_PICTURE (type 6): picture type(4), mime len+mime,
 * desc len+desc, width/height/depth/colors (16), data len+data.
 * returns 0 + malloc'd data on success. */
static int flac_art(FILE *f, unsigned char **out, size_t *outlen,
                    char *mime, size_t mimesz)
{
    unsigned char bh[4];
    if (fread(bh, 1, 4, f) != 4 || memcmp(bh, "fLaC", 4)) return -1;
    for (;;) {
        unsigned char *buf;
        int last, type, len;
        if (fread(bh, 1, 4, f) != 4) return -1;
        last = bh[0] & 0x80;
        type = bh[0] & 0x7F;
        len = get_u24be(bh + 1);
        if (type != 6) {
            if (fseek(f, len, SEEK_CUR)) return -1;
        } else {
            int off = 0, mlen, dlen;
            buf = (unsigned char *)malloc(len);
            if (!buf) return -1;
            if (fread(buf, 1, len, f) != (size_t)len) { free(buf); return -1; }
            if (len < 32) { free(buf); return -1; }
            off = 4;                       /* picture type (skip) */
            mlen = get_u32be(buf + off); off += 4;
            if (off + mlen + 4 > len) { free(buf); return -1; }
            if (mime && mimesz > 0) {
                int cp = mlen < (int)mimesz - 1 ? mlen : (int)mimesz - 1;
                memcpy(mime, buf + off, cp);
                mime[cp] = 0;
            }
            off += mlen;
            dlen = get_u32be(buf + off); off += 4;
            if (off + dlen + 4 > len) { free(buf); return -1; }
            off += dlen;                   /* description (skip) */
            off += 16;                     /* w/h/depth/colors */
            if (off + 4 > len) { free(buf); return -1; }
            {
                int dl = get_u32be(buf + off); off += 4;
                if (off + dl > len) { free(buf); return -1; }
                *out = (unsigned char *)malloc(dl);
                if (!*out) { free(buf); return -1; }
                memcpy(*out, buf + off, dl);
                *outlen = dl;
                free(buf);
                return 0;
            }
        }
        if (last) break;
    }
    return -1;
}

/* ID3v2.3/2.4 APIC frame: encoding(1), mime nul, pictype(1), desc nul, data */
static int id3_art(const unsigned char *buf, int fsize,
                   unsigned char **out, size_t *outlen, char *mime, size_t mimesz)
{
    int off = 1;                 /* text encoding */
    int mlen = 0, dlen = 0;
    while (off + 1 < fsize && buf[off + mlen]) mlen++;
    if (off + mlen + 1 >= fsize) return -1;
    if (mime && mimesz > 0) {
        int cp = mlen < (int)mimesz - 1 ? mlen : (int)mimesz - 1;
        memcpy(mime, buf + off, cp);
        mime[cp] = 0;
    }
    off += mlen + 1 + 1;         /* + mime nul + picture type */
    if (off >= fsize) return -1;
    if (buf[0] == 1 || buf[0] == 2) {        /* utf-16: 2-byte nul */
        while (off + 2 <= fsize && (buf[off] || buf[off + 1])) off += 2;
        off += 2;
    } else {                                  /* latin1 / utf8 */
        while (off < fsize && buf[off]) off++;
        off += 1;
    }
    dlen = fsize - off;
    if (dlen <= 0 || off >= fsize) return -1;
    *out = (unsigned char *)malloc(dlen);
    if (!*out) return -1;
    memcpy(*out, buf + off, dlen);
    *outlen = dlen;
    return 0;
}

/* public: embedded cover art from an fd (consumes it, android SAF style) */
int tag_embedded_art_fd(int fd, unsigned char **out, size_t *outlen,
                        char *mime, size_t mimesz)
{
    FILE *f = fdopen(fd, "rb");
    unsigned char hdr[10];
    int rc = -1;
    *out = NULL;
    *outlen = 0;
    if (mime && mimesz) mime[0] = 0;
    if (!f) { close(fd); return -1; }
    if (fread(hdr, 1, 10, f) != 10) { fclose(f); return -1; }
    if (!memcmp(hdr, "fLaC", 4)) {
        rewind(f);
        rc = flac_art(f, out, outlen, mime, mimesz);
    } else if (!memcmp(hdr, "ID3", 3)) {
        /* re-read whole tag: id3_parse already reads it; simplest is to
         * walk frames here on the same buffer */
        int ver = hdr[3];
        int size = ((hdr[6] & 0x7F) << 21) | ((hdr[7] & 0x7F) << 14) |
                   ((hdr[8] & 0x7F) << 7) | (hdr[9] & 0x7F);
        unsigned char *buf;
        int off = 0;             /* buf = tag body (header read above) */
        if (size > 0 && size < 64 * 1024 * 1024) {
            buf = (unsigned char *)malloc(size);
            if (buf && fread(buf, 1, size, f) == (size_t)size) {
                if ((hdr[5] & 0x40) && off + 4 <= size) {
                    int eh = get_u32be(buf + off);
                    off += 4 + eh;
                }
                while (off + (ver == 2 ? 6 : 10) <= size) {
                    int idlen = ver == 2 ? 3 : 4;
                    int fsize;
                    const char *id = (const char *)buf + off;
                    if (id[0] == 0) break;
                    if (ver == 2) fsize = get_u24be(buf + off + 3);
                    else {
                        fsize = get_u32be(buf + off + 4);
                        if (ver == 4) fsize &= 0x0FFFFFFF;
                    }
                    if (fsize < 0) fsize = 0;
                    if (ver == 2) {
                        if (!memcmp(id, "PIC", 3)) { /* v2.2 unsupported */
                            rc = -1;
                            break;
                        }
                    } else if (!memcmp(id, "APIC", 4)) {
                        rc = id3_art(buf + off + 10, fsize, out, outlen, mime, mimesz);
                        break;
                    }
                    off += idlen + (ver == 2 ? 3 : 6) + fsize;
                }
            }
            free(buf);
        }
    }
    fclose(f);
    return rc;
}

int tag_embedded_art(const char *path, unsigned char **out, size_t *outlen,
                     char *mime, size_t mimesz)
{
    FILE *f = fs_fopen(path, "rb");
    int rc;
    if (!f) return -1;
    rc = tag_embedded_art_fd(dup(fileno(f)), out, outlen, mime, mimesz);
    fclose(f);
    return rc;
}

/* ---------------- FLAC ---------------- */

static int flac_parse(FILE *f, Meta *meta, int *track, int *disc)
{
    unsigned char bh[4];
    unsigned char mg[4];
    int track_ok = 0;
    if (fread(mg, 1, 4, f) != 4 || memcmp(mg, "fLaC", 4)) return -1;
    for (;;) {
        if (fread(bh, 1, 4, f) != 4) return -1;
        int last = bh[0] & 0x80;
        int type = bh[0] & 0x7F;
        int len = get_u24be(bh + 1);
        if (type == 0 && len >= 34) {
            /* STREAMINFO: duration from total_samples / sample_rate */
            unsigned char si[34];
            if (fread(si, 1, 34, f) != 34) return -1;
            uint64_t v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | si[10 + i];
            int rate = (int)(v >> 44);
            uint64_t total = v & ((1ULL << 36) - 1);
            if (meta && rate > 0 && total > 0)
                meta->duration_ms = (int)(total * 1000 / rate);
        } else if (type == 4) {
            /* VORBIS_COMMENT */
            unsigned char *buf = (unsigned char *)malloc(len);
            int off = 0;
            if (fread(buf, 1, len, f) != (size_t)len) { free(buf); return -1; }
            if (len < 4) { free(buf); return -1; }
            off = 4 + get_u32le(buf);
            if (off + 4 > len) { free(buf); return -1; }
            {
                int n = get_u32le(buf + off);
                off += 4;
                for (int i = 0; i < n && off + 4 <= len; i++) {
                    int clen = get_u32le(buf + off);
                    off += 4;
                    if (off + clen > len) break;
                    const unsigned char *c = buf + off;
                    int eq = -1;
                    for (int j = 0; j < clen; j++) if (c[j] == '=') { eq = j; break; }
                    if (eq > 0) {
                        int vl = clen - eq - 1;
                        const char *v = (const char *)c + eq + 1;
                        if (meta && eq == 5 && !strncasecmp((const char *)c, "TITLE", 5) && !meta->title[0]) {
                            memcpy(meta->title, v, vl < (int)sizeof meta->title - 1 ? vl : (int)sizeof meta->title - 1);
                            meta->title[vl < (int)sizeof meta->title - 1 ? vl : (int)sizeof meta->title - 1] = 0;
                        }
                        else if (meta && eq == 6 && !strncasecmp((const char *)c, "ARTIST", 6) && !meta->artist[0]) {
                            memcpy(meta->artist, v, vl < (int)sizeof meta->artist - 1 ? vl : (int)sizeof meta->artist - 1);
                            meta->artist[vl < (int)sizeof meta->artist - 1 ? vl : (int)sizeof meta->artist - 1] = 0;
                        }
                        else if (meta && eq == 5 && !strncasecmp((const char *)c, "ALBUM", 5) && !meta->album[0]) {
                            memcpy(meta->album, v, vl < (int)sizeof meta->album - 1 ? vl : (int)sizeof meta->album - 1);
                            meta->album[vl < (int)sizeof meta->album - 1 ? vl : (int)sizeof meta->album - 1] = 0;
                        }
                        else if (eq == 11 && !strncasecmp((const char *)c, "TRACKNUMBER", 11) && track_ok == 0) {
                            int t = parse_num(v, vl);
                            if (t > 0) { if (track) *track = t; track_ok = 1; }
                        }
                        else if (eq == 10 && !strncasecmp((const char *)c, "DISCNUMBER", 10) && disc && *disc < 0) {
                            int d = parse_num(v, vl);
                            if (d > 0) *disc = d;
                        }
                    }
                    off += clen;
                }
            }
            free(buf);
            return track_ok || (track && *track > 0) || (disc && *disc > 0)
                   || (meta && meta->have_meta) ? 0 : -1;
        } else {
            if (fseek(f, len, SEEK_CUR)) return -1;
        }
        if (last) break;
    }
    return track_ok || (track && *track > 0) ? 0 : -1;
}

/* ---------------- MP3 / ID3v2 ---------------- */

static int id3_parse(FILE *f, Meta *meta, int *track, int *disc)
{
    unsigned char h[10];
    unsigned char *buf;
    int ver, size, off = 0;
    if (fread(h, 1, 10, f) != 10 || memcmp(h, "ID3", 3)) return -1;
    ver = h[3];
    size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14) | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
    if (size <= 0) return -1;
    buf = (unsigned char *)malloc(size);
    if (fread(buf, 1, size, f) != (size_t)size) { free(buf); return -1; }
    if ((h[5] & 0x40) && off + 4 <= size) {          /* extended header */
        int eh = get_u32be(buf + off);
        off += 4 + eh;
    }
    while (off + 6 <= size) {
        int idlen = ver == 2 ? 3 : 4;
        int fsize;
        const char *id;
        if (off + idlen + 3 > size) break;
        id = (const char *)buf + off;
        if (id[0] == 0) break;
        if (ver == 2) {
            fsize = get_u24be(buf + off + 3);
        } else {
            fsize = get_u32be(buf + off + 4);
            if (ver == 4) fsize &= 0x0FFFFFFF;
        }
        if (fsize < 0) fsize = 0;
        if (ver == 2) {
            if (meta && !memcmp(id, "TT2", 3) && !meta->title[0]) id3_text(buf + off + 6, fsize, meta->title, sizeof meta->title);
            else if (meta && !memcmp(id, "TP1", 3) && !meta->artist[0]) id3_text(buf + off + 6, fsize, meta->artist, sizeof meta->artist);
            else if (meta && !memcmp(id, "TAL", 3) && !meta->album[0]) id3_text(buf + off + 6, fsize, meta->album, sizeof meta->album);
            else if (!memcmp(id, "TRK", 3)) { int t = parse_num((const char *)buf + off + 7, fsize - 1); if (t > 0 && track) *track = t; }
            else if (!memcmp(id, "TPA", 3)) { int d = parse_num((const char *)buf + off + 7, fsize - 1); if (d > 0 && disc) *disc = d; }
        } else {
            if (meta && !memcmp(id, "TIT2", 4) && !meta->title[0]) id3_text(buf + off + 10, fsize, meta->title, sizeof meta->title);
            else if (meta && !memcmp(id, "TPE1", 4) && !meta->artist[0]) id3_text(buf + off + 10, fsize, meta->artist, sizeof meta->artist);
            else if (meta && !memcmp(id, "TALB", 4) && !meta->album[0]) id3_text(buf + off + 10, fsize, meta->album, sizeof meta->album);
            else if (!memcmp(id, "TRCK", 4)) {
                int t = parse_num((const char *)buf + off + 11, fsize - 1);
                if (t > 0 && track) *track = t;
            }
            else if (!memcmp(id, "TPOS", 4)) {
                int d = parse_num((const char *)buf + off + 11, fsize - 1);
                if (d > 0 && disc) *disc = d;
            }
        }
        off += idlen + (ver == 2 ? 3 : 6) + fsize;
    }
    free(buf);
    if (meta)
        meta->have_meta = meta->title[0] || meta->artist[0] || meta->album[0] || meta->duration_ms > 0;
    return ((meta && meta->have_meta) || (track && *track > 0) || (disc && *disc > 0)) ? 0 : -1;
}

/* ---------------- public ---------------- */

/* estimate MP3 duration from the first MPEG audio frame header + file size.
 * called with the FILE* positioned right after the ID3 tag. bitrate-based, so
 * VBR is approximate — fine for a library length + scrobble threshold. */
static void mp3_duration(FILE *f, Meta *meta)
{
    unsigned char h[4];
    long start, end;
    unsigned long long bytes;
    int br;
    if (!meta || meta->duration_ms > 0) return;
    if (fread(h, 1, 4, f) != 4) return;
    if (h[0] != 0xFF || (h[1] & 0xE0) != 0xE0) return;   /* no sync */
    int ver = (h[1] >> 3) & 3;      /* 3=MPEG1, 2=MPEG2, 0=2.5 */
    int layer = (h[1] >> 1) & 3;    /* 1=Layer III (mp3) */
    if (layer != 1 || ver == 1) return;
    int brI = (h[2] >> 4) & 0xF;
    if (brI == 0 || brI == 15) return;
    static const int v1[16] = {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0};
    static const int v2[16] = {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0};
    br = (ver == 3) ? v1[brI] : v2[brI];
    if (br <= 0) return;
    start = ftell(f);
    if (start < 0) return;
    if (fseek(f, 0, SEEK_END) != 0) return;
    end = ftell(f);
    fseek(f, start, SEEK_SET);
    bytes = (unsigned long long)(end - start);
    if ((long)bytes <= 0) return;
    meta->duration_ms = (int)(bytes * 8 / br);   /* br kbps -> bytes*8/br = ms */
}

static int ogg_parse(FILE *f, Meta *meta, int *track, int *disc);
static int mp4_parse(FILE *f, Meta *meta, int *track, int *disc);

/* shared body: parse tags from an open file (caller owns *f).
 * portable core — the android port passes SAF file descriptors here. */
static int tag_read_f(FILE *f, Meta *meta, int *track, int *disc)
{
    unsigned char magic[8];
    int tr = -1, dc = -1;
    int rc = -1;
    if (!f) return -1;
    if (meta) memset(meta, 0, sizeof(*meta));
    if (fread(magic, 1, 8, f) != 8) return -1;
    rewind(f);
    if (!memcmp(magic, "fLaC", 4)) {
        rc = flac_parse(f, meta, &tr, &dc);
    } else if (!memcmp(magic, "ID3", 3)) {
        rc = id3_parse(f, meta, &tr, &dc);
        if (meta && meta->duration_ms <= 0)
            mp3_duration(f, meta);   /* f is positioned after the ID3 tag */
    } else if (!memcmp(magic, "OggS", 4)) {
        rc = ogg_parse(f, meta, &tr, &dc);
    } else if (!memcmp(magic + 4, "ftyp", 4)) {
        rc = mp4_parse(f, meta, &tr, &dc);
    }
    if (meta) {
        meta->have_meta = meta->title[0] || meta->artist[0] || meta->album[0] || meta->duration_ms > 0;
        if (tr > 0) meta->track = tr;
        if (dc > 0) meta->disc = dc;
        /* success if we got tags OR a track/disc number — a FLAC with
         * title/artist/album but no tracknumber must still count as read,
         * not get discarded by flac_parse's internal -1. */
        rc = (meta->have_meta || tr > 0 || dc > 0) ? 0 : -1;
    }
    if (track) *track = tr;
    if (disc) *disc = dc;
    return rc;
}

int tag_read_meta(const char *path, Meta *meta)
{
    FILE *f = fs_fopen(path, "rb");
    int rc;
    if (!f) return -1;
    rc = tag_read_f(f, meta, NULL, NULL);
    fclose(f);
    return rc;
}

int tag_trackinfo(const char *path, int *track, int *disc)
{
    FILE *f = fs_fopen(path, "rb");
    int rc;
    if (!f) return -1;
    rc = tag_read_f(f, NULL, track, disc);
    fclose(f);
    if (*track <= 0 && *disc <= 0) rc = -1;
    return rc;
}

/* android: tags from an already-open fd (SAF files have no path).
 * the fd is CONSUMED and closed by this call — JNI callers must
 * ParcelFileDescriptor.detachFd() first and not touch it after. */
int tag_read_meta_fd(int fd, Meta *meta)
{
    FILE *f = fdopen(fd, "rb");
    int rc;
    if (!f) { close(fd); return -1; }
    rc = tag_read_f(f, meta, NULL, NULL);
    fclose(f);
    return rc;
}

int tag_trackinfo_fd(int fd, int *track, int *disc)
{
    FILE *f = fdopen(fd, "rb");
    int rc;
    if (!f) { close(fd); return -1; }
    rc = tag_read_f(f, NULL, track, disc);
    fclose(f);
    if (*track <= 0 && *disc <= 0) rc = -1;
    return rc;
}

/* ---------------- OGG/Opus ---------------- */

static unsigned long long get_u64le(const unsigned char *p) {
    unsigned long long v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}
static unsigned long long get_u64be(const unsigned char *p) {
    unsigned long long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | p[i];
    return v;
}

/* parse a Vorbis-style comment block (vendor_length + comments). Opus and
 * FLAC both use one; pass a pointer at the comment block start. */
static void apply_comments(const unsigned char *buf, int len,
                           Meta *meta, int *track, int *disc, int *any)
{
    int off, n, i;
    if (len < 8) return;
    off = 4 + (int)get_u32le(buf);
    if (off + 4 > len) return;
    n = (int)get_u32le(buf + off); off += 4;
    for (i = 0; i < n && off + 4 <= len; i++) {
        int clen = (int)get_u32le(buf + off); off += 4;
        if (off + clen > len) break;
        const unsigned char *c = buf + off;
        int eq = -1;
        for (int j = 0; j < clen; j++) if (c[j] == '=') { eq = j; break; }
        if (eq > 0) {
            const char *v = (const char *)c + eq + 1;
            int vl = clen - eq - 1;
#define SET_TAG(dst) do { \
    int cc = vl < (int)sizeof meta->dst - 1 ? vl : (int)sizeof meta->dst - 1; \
    memcpy(meta->dst, v, cc); meta->dst[cc] = 0; *any = 1; } while (0)
            if (meta && eq == 5 && !strncasecmp((const char *)c, "TITLE", 5) && !meta->title[0]) SET_TAG(title);
            else if (meta && eq == 6 && !strncasecmp((const char *)c, "ARTIST", 6) && !meta->artist[0]) SET_TAG(artist);
            else if (meta && eq == 5 && !strncasecmp((const char *)c, "ALBUM", 5) && !meta->album[0]) SET_TAG(album);
            else if (eq == 11 && !strncasecmp((const char *)c, "TRACKNUMBER", 11) && track && *track < 0) {
                int t = parse_num(v, vl); if (t > 0) { *track = t; *any = 1; }
            }
            else if (eq == 10 && !strncasecmp((const char *)c, "DISCNUMBER", 10) && disc && *disc < 0) {
                int d = parse_num(v, vl); if (d > 0) *disc = d;
            }
#undef SET_TAG
        }
        off += clen;
    }
}

/* Opus streams: Ogg pages; tags live in the OpusTags comment packet near the
 * start. duration = last page granule / 48000. */
static int ogg_parse(FILE *f, Meta *meta, int *track, int *disc)
{
    unsigned char *buf;
    long end, blen;
    int any = 0;
    long rate = 48000;   /* Opus default; Vorbis overrides from id header */
    fseek(f, 0, SEEK_END); end = ftell(f); fseek(f, 0, SEEK_SET);
    blen = end < 131072 ? end : 131072;            /* OpusTags is early + small */
    if (blen <= 0) return -1;
    buf = (unsigned char *)malloc((size_t)blen);
    if (fread(buf, 1, (size_t)blen, f) != (size_t)blen) { free(buf); return -1; }
    for (long i = 0; i + 8 <= blen; i++) {
        /* Opus: "OpusTags" comment packet (8-byte magic, then comments). */
        if (!memcmp(buf + i, "OpusTags", 8)) {
            apply_comments(buf + i + 8, (int)(blen - i - 8), meta, track, disc, &any);
            continue;
        }
        /* Vorbis: comment packet is \x03 "vorbis" (7 bytes) + framing, then
         * the same vorbis-format comment block (vendor_len + comments). */
        if (!memcmp(buf + i, "\x03vorbis", 7)) {
            apply_comments(buf + i + 7, (int)(blen - i - 7), meta, track, disc, &any);
            continue;
        }
        /* Vorbis identification header: \x01 "vorbis", then version(4),
         * channels(1), sample_rate(4 LE) at offset +12. Capture the rate so
         * granule→ms is correct for Vorbis (commonly 44100, not 48000). */
        if (!memcmp(buf + i, "\x01vorbis", 7)) {
            if (i + 16 <= blen)
                rate = (long)get_u32le(buf + i + 12);
            continue;
        }
    }
    free(buf);
    /* duration: granule of the last Ogg page / sample rate. */
    if (meta) {
        unsigned char h[27];
        unsigned long long gran = 0;
        long p0 = end - 66000 < 0 ? 0 : end - 66000;
        for (long p = p0; p + 27 <= end; p++) {
            fseek(f, p, SEEK_SET);
            if (fread(h, 1, 27, f) != 27) break;
            if (memcmp(h, "OggS", 4)) continue;
            gran = get_u64le(h + 6);
        }
        if (gran > 0 && rate > 0)
            meta->duration_ms = (int)(gran * 1000 / rate);
    }
    return (any || (meta && meta->duration_ms > 0)) ? 0 : -1;
}

/* ---------------- MP4 / M4A ---------------- */

static long mp4_u32(FILE *f, long off) {
    unsigned char b[4];
    if (fseek(f, off, SEEK_SET)) return 0;
    if (fread(b, 1, 4, f) != 4) return 0;
    return (long)get_u32be(b);
}
static unsigned long long mp4_u64(FILE *f, long off) {
    unsigned char b[8];
    if (fseek(f, off, SEEK_SET)) return 0;
    if (fread(b, 1, 8, f) != 8) return 0;
    return get_u64be(b);
}

/* read a UTF-8 tag value out of an ilst item's data box (points at item's
 * content start, which is the `data` box) */
static const char *ilst_data(FILE *f, long content, long end, char *out, int outsz)
{
    unsigned char h[16];
    long payload;
    if (content + 16 > end) return NULL;
    if (fseek(f, content, SEEK_SET)) return NULL;
    if (fread(h, 1, 16, f) != 16) return NULL;
    payload = content + 16;
    if (payload >= end) return NULL;
    long n = end - payload;
    if (n > outsz - 1) n = outsz - 1;
    if (fseek(f, payload, SEEK_SET)) return NULL;
    if (fread(out, 1, (size_t)n, f) != (size_t)n) return NULL;
    out[n] = 0;
    /* strip trailing NULs */
    while (n > 0 && out[n - 1] == 0) out[--n] = 0;
    return out;
}

static void mp4_walk(FILE *f, long start, long end, Meta *meta,
                     int *track, int *disc, int *any)
{
    long off = start;
    while (off + 8 <= end) {
        unsigned char h[8];
        if (fseek(f, off, SEEK_SET)) break;
        if (fread(h, 1, 8, f) != 8) break;
        long boxend = (long)((unsigned int)get_u32be(h) + off);
        if (boxend <= off || boxend > end) boxend = end;
        char type[5] = {h[4], h[5], h[6], h[7], 0};
        long content = off + 8;
        if (!strcmp(type, "meta")) content += 4;    /* fullbox version/flags */
        if (!strcmp(type, "mvhd")) {
            int ver = mp4_u32(f, content) >> 24;
            long ts, dur;
            if (ver == 1) { ts = mp4_u32(f, content + 20); dur = (long)mp4_u64(f, content + 24); }
            else          { ts = mp4_u32(f, content + 12); dur = mp4_u32(f, content + 16); }
            if (meta && ts > 0 && dur > 0 && meta->duration_ms <= 0)
                meta->duration_ms = (int)((unsigned long long)dur * 1000 / ts);
        } else if (!memcmp(type, "\xA9" "nam", 4)) {
            char val[512];
            if (meta && !meta->title[0] && ilst_data(f, content, boxend, val, sizeof val))
                { strncpy(meta->title, val, sizeof meta->title - 1); meta->title[sizeof meta->title - 1] = 0; *any = 1; }
        } else if (!memcmp(type, "\xA9" "ART", 4) || !memcmp(type, "aART", 4)) {
            char val[512];
            if (meta && !meta->artist[0] && ilst_data(f, content, boxend, val, sizeof val))
                { strncpy(meta->artist, val, sizeof meta->artist - 1); meta->artist[sizeof meta->artist - 1] = 0; *any = 1; }
        } else if (!memcmp(type, "\xA9" "alb", 4)) {
            char val[512];
            if (meta && !meta->album[0] && ilst_data(f, content, boxend, val, sizeof val))
                { strncpy(meta->album, val, sizeof meta->album - 1); meta->album[sizeof meta->album - 1] = 0; *any = 1; }
        } else if (!memcmp(type, "trkn", 4)) {
            unsigned char d[8];
            if (content + 16 + 8 <= boxend) {
                if (!fseek(f, content + 16, SEEK_SET) && fread(d, 1, 8, f) == 8) {
                    int t = (d[2] << 8) | d[3];
                    if (t > 0 && track && *track < 0) *track = t;
                }
            }
        }
        if (off == boxend) break;
        off = boxend;
        if (!strcmp(type, "moov") || !strcmp(type, "udta")
                || !strcmp(type, "meta") || !strcmp(type, "ilst"))
            mp4_walk(f, content, boxend, meta, track, disc, any);
    }
}

static int mp4_parse(FILE *f, Meta *meta, int *track, int *disc)
{
    long end, off = 0;
    int any = 0;
    unsigned char h[8];
    fseek(f, 0, SEEK_END); end = ftell(f); fseek(f, 0, SEEK_SET);
    if (end < 12) return -1;
    if (fread(h, 1, 8, f) != 8) return -1;
    if (memcmp(h + 4, "ftyp", 4) != 0) return -1;    /* not an MP4 family file */
    while (off + 8 <= end) {
        if (fseek(f, off, SEEK_SET)) break;
        if (fread(h, 1, 8, f) != 8) break;
        long boxend = (long)((unsigned int)get_u32be(h) + off);
        if (boxend <= off || boxend > end) boxend = end;
        if (!memcmp(h + 4, "moov", 4))
            mp4_walk(f, off + 8, boxend, meta, track, disc, &any);
        if (boxend >= end) break;
        off = boxend;
    }
    return any ? 0 : -1;
}
