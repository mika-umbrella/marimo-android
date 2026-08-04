/* core_api.h — the C surface the android app binds to via JNI.
 *
 * Design rules:
 *  - NO structs across the JNI boundary: everything is flat buffers or
 *    opaque handles, so the Kotlin side never touches C layouts.
 *  - SAF files have no paths: tag reading takes a raw fd (the app passes
 *    ParcelFileDescriptor.detachFd()); the fd is CONSUMED by the call.
 *  - Queue "paths" are opaque tokens (content:// URIs work fine — the C
 *    side only orders and stores them; the app resolves playback).
 *
 * Binding: System.loadLibrary("marimo_core") + the usual extern "C"
 * wrapper in the JNI shim (jni/marimo_core.c), which calls these.
 */
#ifndef MARIMO_CORE_API_H
#define MARIMO_CORE_API_H

/* ---- tags (core/tags.c) --------------------------------------------- */

/* full tag read from an fd. fills the flat buffers (NUL-terminated) and
 * returns 0 on success. consumes fd. title/artist/album may be NULL. */
int core_tag_read_fd(int fd,
                     char *title, int title_cap,
                     char *artist, int artist_cap,
                     char *album, int album_cap,
                     int *duration_ms, int *track, int *disc);

/* just disc/track (for sorting a folder queue). consumes fd. */
int core_tag_trackinfo_fd(int fd, int *track, int *disc);

/* ---- queue (core/queue.c) ------------------------------------------- */

/* opaque handle; the app owns one queue, C owns the memory + mutex */
void *core_queue_new(int shuffle, int repeat);
void  core_queue_free(void *q);
/* returns the new item index */
int   core_queue_add(void *q, const char *token, const char *name, long long size);
int   core_queue_remove(void *q, int idx);
/* index to play after cur (respects shuffle/repeat); -1 = stop */
int   core_queue_next(void *q);
/* set the current index (before playback starts) */
void  core_queue_set_cur(void *q, int idx);
int   core_queue_len(void *q);

/* ---- misc ------------------------------------------------------------ */

/* md5 hex digest (scrobble auth). out must hold 33 bytes. */
void core_md5_hex(const unsigned char *data, size_t len, char *out);

#endif
