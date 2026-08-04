/* core_selftest.c — standalone test harness for the portable marimo core.
 * No SDL, no mpv, no UI — md5, queue, fs, tags (path AND fd variants).
 * Build:  gcc -std=c11 -Wall -Wextra -pedantic -O2 -o core_selftest *.c
 * Usage:  ./core_selftest [music_dir]   (default: ~/Music)
 * This is the file the android port runs on-device first. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>

#include "md5.h"
#include "queue.h"
#include "fs.h"
#include "tags.h"

static int fails = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { printf("FAIL: %s\n", msg); fails++; } \
} while (0)

static void test_md5(void)
{
    char h[33];
    md5_hex("", 0, h);
    CHECK(!strcmp(h, "d41d8cd98f00b204e9800998ecf8427e"), "md5 empty");
    md5_hex("abc", 3, h);
    CHECK(!strcmp(h, "900150983cd24fb0d6963f7d28e17f72"), "md5 abc");
    md5_hex("The quick brown fox jumps over the lazy dog", 43, h);
    CHECK(!strcmp(h, "9e107d9d372bb6826bd81d3542a419d6"), "md5 fox");
    printf("md5: %s\n", fails ? "FAIL" : "ok");
}

static void test_queue(void)
{
    Queue q;
    q_init(&q, 0, 1);
    for (int i = 0; i < 5; i++) {
        char p[64], n[64];
        snprintf(p, sizeof p, "/tmp/t%d.flac", i);
        snprintf(n, sizeof n, "t%d", i);
        q_add(&q, p, n, 0);
    }
    q.cur = 0;
    CHECK(q_next(&q) == 1, "next");
    q.cur = 4;
    CHECK(q_next(&q) == 0, "wrap with repeat");
    q.repeat = 0;
    CHECK(q_next(&q) == -1, "no wrap without repeat");
    q_remove(&q, 2);
    CHECK(q.n == 4, "remove");
    CHECK(q_find(&q, "/tmp/t3.flac") == 2, "find");
    q.shuffle = 1;
    CHECK(q_next(&q) >= 0 && q_next(&q) < 4, "shuffle range");
    q_free(&q);
    printf("queue: %s\n", fails ? "FAIL" : "ok");
}

static void test_fs(void)
{
    /* a real japanese-named path through fs_fopen/fs_stat */
    const char *base = getenv("HOME");
    char path[1024];
    FILE *f;
    struct stat st;
    if (!base) base = "/tmp";
    snprintf(path, sizeof path, "%s/.marimo_fs_test_日本語.txt", base);
    f = fs_fopen(path, "w");
    CHECK(f != NULL, "fs_fopen write japanese path");
    if (f) {
        fputs("moss", f);
        fclose(f);
    }
    CHECK(fs_stat(path, &st) == 0, "fs_stat japanese path");
    if (fs_stat(path, &st) == 0)
        CHECK(st.st_size == 4, "fs_stat size");
    unlink(path);
    printf("fs: %s\n", fails ? "FAIL" : "ok");
}

static void test_tags(const char *dir)
{
    DIR *d;
    struct dirent *de;
    int tested = 0, fd = -1;
    d = opendir(dir);
    CHECK(d != NULL, "opendir music root");
    if (!d) return;
    /* find the first directory containing at least one audio file */
    while ((de = readdir(d)) && !tested) {
        char sub[2048], file[2048];
        DIR *sd;
        struct dirent *se;
        if (de->d_name[0] == '.') continue;
        snprintf(sub, sizeof sub, "%s/%s", dir, de->d_name);
        sd = opendir(sub);
        if (!sd) continue;
        while ((se = readdir(sd)) && !tested) {
            int l = (int)strlen(se->d_name);
            const char *ext;
            Meta m;
            int tr = -1, dc = -1;
            if (l < 5) continue;
            ext = se->d_name + l - 5;
            if (strcasecmp(ext, ".flac") && strcasecmp(ext, ".mp3")) continue;
            snprintf(file, sizeof file, "%s/%s", sub, se->d_name);
            /* path variant */
            CHECK(tag_read_meta(file, &m) == 0, "tag_read_meta path");
            if (m.title[0] || m.artist[0])
                printf("tags: '%s' by '%s' [%s] %d:%02d\n",
                       m.title[0] ? m.title : "(no title)", m.artist,
                       m.album, m.duration_ms / 60000, (m.duration_ms / 1000) % 60);
            CHECK(tag_trackinfo(file, &tr, &dc) == 0, "tag_trackinfo path");
            /* fd variant: same file, must agree */
            fd = open(file, O_RDONLY);
            CHECK(fd >= 0, "open file");
            if (fd >= 0) {
                Meta m2;
                CHECK(tag_read_meta_fd(fd, &m2) == 0, "tag_read_meta_fd");
                if (m2.have_meta && m.have_meta) {
                    CHECK(!strcmp(m.title, m2.title), "fd/path title agree");
                    CHECK(m.duration_ms == m2.duration_ms, "fd/path duration agree");
                }
                fd = -1;
            }
            tested = 1;
        }
        closedir(sd);
    }
    closedir(d);
    CHECK(tested, "found a testable audio file");
    printf("tags: %s\n", fails ? "FAIL" : "ok");
}

int main(int argc, char **argv)
{
    const char *dir = argc > 1 ? argv[1] : NULL;
    char def[2048];
    printf("== marimo core selftest ==\n");
    if (!dir) {
        const char *home = getenv("HOME");
        snprintf(def, sizeof def, "%s/Music", home ? home : ".");
        dir = def;
    }
    test_md5();
    test_queue();
    test_fs();
    test_tags(dir);
    if (fails) { printf("== FAILED (%d) ==\n", fails); return 1; }
    printf("== CORE SELFTEST PASSED ==\n");
    return 0;
}
