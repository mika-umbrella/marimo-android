/* tags.h — fast tag reader for FLAC + MP3 (ID3v2).
 * Microseconds per file, no mpv needed — used for folder queue ordering
 * (disc/track numbers) and as the fast path for queue metadata. */
#ifndef MIKA_TAGS_H
#define MIKA_TAGS_H

#include "queue.h"

/* full tag read. returns 0 + have_meta on success (title/artist/album/duration) */
int tag_read_meta(const char *path, Meta *meta);
/* just disc/track numbers (for sorting). returns 0 if either was found */
int tag_trackinfo(const char *path, int *track, int *disc);

/* android: fd-based variants (SAF files have no path). consumes the fd. */
int tag_read_meta_fd(int fd, Meta *meta);
int tag_trackinfo_fd(int fd, int *track, int *disc);

/* embedded cover art (FLAC METADATA_BLOCK_PICTURE / ID3 APIC).
 * returns 0 + malloc'd raw image bytes (caller frees). fd variant
 * consumes the fd; mime (e.g. "image/jpeg") may be NULL. */
int tag_embedded_art_fd(int fd, unsigned char **out, size_t *outlen,
                        char *mime, size_t mimesz);
int tag_embedded_art(const char *path, unsigned char **out, size_t *outlen,
                     char *mime, size_t mimesz);

#endif
