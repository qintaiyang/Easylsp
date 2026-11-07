#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <dlfcn.h>
typedef struct { uint64_t flags; int library_fd; } android_dlextinfo;
#define ANDROID_DLEXT_USE_LIBRARY_FD 16
static inline void *android_dlopen_ext(const char *name, int flags, const android_dlextinfo *extension) {
    (void)name;
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", extension->library_fd);
    return dlopen(path, flags);
}
