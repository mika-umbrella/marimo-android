#include "tags.h"
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: %s <flac>\n", argv[0]); return 1; }
    int tr = -1, dc = -1;
    int rc1 = tag_trackinfo(argv[1], &tr, &dc);
    printf("trackinfo: rc=%d track=%d disc=%d\n", rc1, tr, dc);
    Meta m;
    int rc2 = tag_read_meta(argv[1], &m);
    printf("read_meta: rc=%d have_meta=%d dur=%d\n", rc2, m.have_meta, m.duration_ms);
    printf("  title='%s'\n  artist='%s'\n  album='%s'\n", m.title, m.artist, m.album);
    return 0;
}
