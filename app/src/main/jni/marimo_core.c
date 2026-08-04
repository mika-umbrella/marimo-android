/* marimo_core.c — JNI shim: exposes the portable C core to the android app.
 * Built together with the core sources into libmarimo_core.so by android/Makefile. */
#pragma GCC diagnostic ignored "-Wunused-parameter"   /* JNI signatures are fixed by the JVM */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <dirent.h>

#include "md5.h"
#include "queue.h"
#include "tags.h"
#include "fs.h"

/* ---------------- selftest ---------------- */

static void st_md5(char *out, size_t n, int *fails)
{
    char h[33];
    md5_hex("", 0, h);
    if (strcmp(h, "d41d8cd98f00b204e9800998ecf8427e")) (*fails)++;
    md5_hex("abc", 3, h);
    if (strcmp(h, "900150983cd24fb0d6963f7d28e17f72")) (*fails)++;
    md5_hex("The quick brown fox jumps over the lazy dog", 43, h);
    if (strcmp(h, "9e107d9d372bb6826bd81d3542a419d6")) (*fails)++;
    snprintf(out, n, "md5: %s\n", *fails ? "FAIL" : "ok");
}

static void st_queue(char *out, size_t n, int *fails)
{
    Queue q;
    int bad = 0;
    q_init(&q, 0, 1);
    for (int i = 0; i < 5; i++) {
        char p[64];
        snprintf(p, sizeof p, "/t%d", i);
        q_add(&q, p, p, 0);
    }
    q.cur = 0;
    if (q_next(&q) != 1) bad = 1;
    q.cur = 4;
    if (q_next(&q) != 0) bad = 1;
    q.repeat = 0;
    if (q_next(&q) != -1) bad = 1;
    q_remove(&q, 2);
    if (q.n != 4) bad = 1;
    if (q_find(&q, "/t3") != 2) bad = 1;
    q_free(&q);
    if (bad) (*fails)++;
    snprintf(out, n, "queue: %s\n", bad ? "FAIL" : "ok");
}

static const char *audio_exts[] = { ".flac", ".mp3", ".ogg", ".opus",
                                    ".m4a", ".wav", ".aac", ".ape", NULL };
static int is_audio(const char *name)
{
    size_t l = strlen(name);
    for (int i = 0; audio_exts[i]; i++) {
        size_t e = strlen(audio_exts[i]);
        if (l > e && !strcasecmp(name + l - e, audio_exts[i])) return 1;
    }
    return 0;
}

/* find the first audio file one level deep and parse its tags */
static void st_tags(const char *dir, char *out, size_t n, int *fails)
{
    DIR *d = opendir(dir);
    struct dirent *de;
    if (!d) {
        snprintf(out, n, "tags: %s not readable\n", dir);
        return;
    }
    while ((de = readdir(d))) {
        char path[1024];
        Meta m;
        if (de->d_name[0] == '.') continue;
        if (!is_audio(de->d_name)) continue;
        snprintf(path, sizeof path, "%s/%s", dir, de->d_name);
        if (tag_read_meta(path, &m) != 0) continue;
        snprintf(out, n, "tags: '%s' by '%s' [%s] %d:%02d\n",
                 m.title[0] ? m.title : "(no title)", m.artist, m.album,
                 m.duration_ms / 60000, (m.duration_ms / 1000) % 60);
        closedir(d);
        return;
    }
    closedir(d);
    snprintf(out, n, "tags: no audio files in %s\n", dir);
}

JNIEXPORT jstring JNICALL
Java_moe_umbrella_marimo_NativeBridge_runSelftest(JNIEnv *env, jclass clazz,
                                                  jstring musicDir)
{
    char buf[4096];
    int fails = 0;
    const char *dir = musicDir ? (*env)->GetStringUTFChars(env, musicDir, NULL) : NULL;
    buf[0] = 0;
    strcat(buf, "== marimo core on android ==\n");
    st_md5(buf + strlen(buf), sizeof buf - strlen(buf), &fails);
    st_queue(buf + strlen(buf), sizeof buf - strlen(buf), &fails);
    st_tags(dir ? dir : "/data/local/tmp", buf + strlen(buf),
            sizeof buf - strlen(buf), &fails);
    if (dir) (*env)->ReleaseStringUTFChars(env, musicDir, dir);
    strcat(buf, fails ? "== FAILED ==\n" : "== PASSED ==\n");
    return (*env)->NewStringUTF(env, buf);
}

/* ---------------- tags (path + fd) ---------------- */

static jint fill_tags(JNIEnv *env, const Meta *m, jbyteArray title, jbyteArray artist,
                      jbyteArray album, jintArray dur, jintArray track, jintArray disc)
{
    jsize cap;
    jint v;
#define PUT(ARR, SRC) do { \
    if (ARR) { \
        cap = (*env)->GetArrayLength(env, ARR); \
        if (cap > 0) { \
            jbyte tmp[512]; \
            size_t l = strlen(SRC); \
            if (l > (size_t)cap - 1) l = (size_t)cap - 1; \
            memcpy(tmp, SRC, l); \
            tmp[l] = 0; \
            (*env)->SetByteArrayRegion(env, ARR, 0, (jsize)(l + 1), tmp); \
        } \
    } \
} while (0)

    PUT(title, m->title);
    PUT(artist, m->artist);
    PUT(album, m->album);
    v = m->duration_ms; (*env)->SetIntArrayRegion(env, dur, 0, 1, &v);
    v = m->track;       (*env)->SetIntArrayRegion(env, track, 0, 1, &v);
    v = m->disc;        (*env)->SetIntArrayRegion(env, disc, 0, 1, &v);
    return 0;
#undef PUT
}

JNIEXPORT jint JNICALL
Java_moe_umbrella_marimo_NativeBridge_tagReadPath(JNIEnv *env, jclass clazz,
                                                  jstring path,
                                                  jbyteArray title, jbyteArray artist,
                                                  jbyteArray album,
                                                  jintArray dur, jintArray track,
                                                  jintArray disc)
{
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    Meta m;
    int rc;
    if (!p) return -1;
    rc = tag_read_meta(p, &m);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (rc != 0) return -1;
    return fill_tags(env, &m, title, artist, album, dur, track, disc);
}

JNIEXPORT jint JNICALL
Java_moe_umbrella_marimo_NativeBridge_tagReadFd(JNIEnv *env, jclass clazz,
                                                jint fd,
                                                jbyteArray title, jbyteArray artist,
                                                jbyteArray album,
                                                jintArray dur, jintArray track,
                                                jintArray disc)
{
    Meta m;
    int rc = tag_read_meta_fd(fd, &m);
    if (rc != 0) return -1;
    return fill_tags(env, &m, title, artist, album, dur, track, disc);
}

/* ---------------- embedded art ---------------- */

JNIEXPORT jbyteArray JNICALL
Java_moe_umbrella_marimo_NativeBridge_embeddedArtFd(JNIEnv *env, jclass clazz,
                                                    jint fd)
{
    unsigned char *data;
    size_t len;
    jbyteArray arr;
    if (tag_embedded_art_fd(fd, &data, &len, NULL, 0) != 0) return NULL;
    arr = (*env)->NewByteArray(env, (jsize)len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (jbyte *)data);
    free(data);
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_moe_umbrella_marimo_NativeBridge_embeddedArtPath(JNIEnv *env, jclass clazz,
                                                      jstring path)
{
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    unsigned char *data;
    size_t len;
    jbyteArray arr = NULL;
    if (!p) return NULL;
    if (tag_embedded_art(p, &data, &len, NULL, 0) == 0) {
        arr = (*env)->NewByteArray(env, (jsize)len);
        if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (jbyte *)data);
        free(data);
    }
    (*env)->ReleaseStringUTFChars(env, path, p);
    return arr;
}

/* ---------------- queue (opaque handle) ---------------- */

JNIEXPORT jlong JNICALL
Java_moe_umbrella_marimo_NativeBridge_queueNew(JNIEnv *env, jclass clazz,
                                               jint shuffle, jint repeat)
{
    Queue *q = (Queue *)calloc(1, sizeof(Queue));
    if (q) q_init(q, shuffle, repeat);
    return (jlong)(intptr_t)q;
}

JNIEXPORT jint JNICALL
Java_moe_umbrella_marimo_NativeBridge_queueAdd(JNIEnv *env, jclass clazz,
                                               jlong q, jstring token,
                                               jstring name, jlong size)
{
    const char *t = token ? (*env)->GetStringUTFChars(env, token, NULL) : "";
    const char *nm = name ? (*env)->GetStringUTFChars(env, name, NULL) : "";
    int idx = q_add((Queue *)(intptr_t)q, t, nm, size);
    if (token) (*env)->ReleaseStringUTFChars(env, token, t);
    if (name) (*env)->ReleaseStringUTFChars(env, name, nm);
    return idx;
}

JNIEXPORT jint JNICALL
Java_moe_umbrella_marimo_NativeBridge_queueNext(JNIEnv *env, jclass clazz,
                                                jlong q)
{
    return q_next((Queue *)(intptr_t)q);
}

JNIEXPORT jint JNICALL
Java_moe_umbrella_marimo_NativeBridge_queueLen(JNIEnv *env, jclass clazz,
                                               jlong q)
{
    return ((Queue *)(intptr_t)q)->n;
}

JNIEXPORT void JNICALL
Java_moe_umbrella_marimo_NativeBridge_queueFree(JNIEnv *env, jclass clazz,
                                                jlong q)
{
    Queue *p = (Queue *)(intptr_t)q;
    if (p) { q_free(p); free(p); }
}
