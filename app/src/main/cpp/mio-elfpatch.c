#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static const char OLD_PREFIX[21] = "/data/data/com.termux";
static const char NEW_PREFIX[21] = "/data/data/com.mio///";

enum {
    PREFIX_LEN = 21,
    BUF_CAP = 1048576
};

static int write_all_at(int fd, const char *data, size_t len, off_t offset) {
    size_t done = 0;
    while (done < len) {
        ssize_t w = pwrite(fd, data + done, len - done, offset + (off_t)done);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (w == 0) return -1;
        done += (size_t)w;
    }
    return 0;
}

static int patch_file(const char *path, int elf_only, unsigned char *buf) {
    struct stat st;
    if (lstat(path, &st) != 0) {
        fprintf(stderr, "mio-elfpatch: lstat(%s): %s\n", path, strerror(errno));
        return 1;
    }
    if (!S_ISREG(st.st_mode)) {
        return 0;
    }
    if (st.st_size < (off_t)PREFIX_LEN) {
        return 0;
    }

    if (access(path, W_OK) != 0) {
        if (chmod(path, st.st_mode | S_IWUSR) != 0) {
            fprintf(stderr, "mio-elfpatch: chmod(%s): %s\n", path, strerror(errno));
            return 1;
        }
    }

    int fd = open(path, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "mio-elfpatch: open(%s): %s\n", path, strerror(errno));
        return 1;
    }

    if (elf_only) {
        unsigned char magic[4];
        ssize_t m = pread(fd, magic, 4, 0);
        if (m < 4 || magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F') {
            close(fd);
            return 1;
        }
    }

    off_t offset = 0;
    while (offset < st.st_size) {
        size_t to_read = BUF_CAP;
        off_t rem = st.st_size - offset;
        if (rem < (off_t)to_read) {
            to_read = (size_t)rem;
        }

        size_t got = 0;
        while (got < to_read) {
            ssize_t r = pread(fd, buf + got, to_read - got, offset + (off_t)got);
            if (r < 0) {
                if (errno == EINTR) continue;
                fprintf(stderr, "mio-elfpatch: pread(%s): %s\n", path, strerror(errno));
                close(fd);
                return 1;
            }
            if (r == 0) break;
            got += (size_t)r;
        }

        if (got < PREFIX_LEN) break;

        size_t limit = got - PREFIX_LEN;
        for (size_t i = 0; i <= limit; i++) {
            if (buf[i] == '/' && memcmp(buf + i, OLD_PREFIX, PREFIX_LEN) == 0) {
                if (write_all_at(fd, NEW_PREFIX, PREFIX_LEN, offset + (off_t)i) != 0) {
                    fprintf(stderr, "mio-elfpatch: pwrite(%s): %s\n", path, strerror(errno));
                    close(fd);
                    return 1;
                }
                i += PREFIX_LEN - 1;
            }
        }

        if (offset + (off_t)got >= st.st_size) break;
        offset += (off_t)(got - (PREFIX_LEN - 1));
    }

    if (close(fd) != 0) {
        fprintf(stderr, "mio-elfpatch: close(%s): %s\n", path, strerror(errno));
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    int elf_only = 1;
    int argi = 1;
    if (argi < argc && (strcmp(argv[argi], "-a") == 0 || strcmp(argv[argi], "--all") == 0)) {
        elf_only = 0;
        argi++;
    }
    if (argi >= argc) {
        fprintf(stderr, "usage: mio-elfpatch [-a|--all] <file>...\n");
        return 2;
    }

    unsigned char *buf = malloc(BUF_CAP);
    if (!buf) {
        fprintf(stderr, "mio-elfpatch: out of memory\n");
        return 1;
    }

    int rc = 0;
    for (; argi < argc; argi++) {
        if (patch_file(argv[argi], elf_only, buf) != 0) {
            rc = 1;
        }
    }

    free(buf);
    return rc;
}
