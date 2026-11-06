#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 5 || strcmp(argv[1], "--config") || strcmp(argv[3], "--")) {
        fprintf(stderr, "usage: easylsp-native-run --config /absolute/config.json -- /absolute/program [args...]\n"); return 2;
    }
    char config[PATH_MAX], self[PATH_MAX], preload[PATH_MAX * 6];
    if (!realpath(argv[2], config) || argv[4][0] != '/') { perror("absolute paths"); return 2; }
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length < 0) { perror("launcher path"); return 125; } self[length] = 0;
    char *slash = strrchr(self, '/'); *slash = 0;
    const char *old = getenv("LD_PRELOAD");
    int count = snprintf(preload, sizeof(preload), "%s/libshadowhook.so:%s/libeasylsp_native.so:%s/libeasylsp_bootstrap.so%s%s", self, self, self, old && *old ? ":" : "", old ? old : "");
    if (count < 0 || (size_t)count >= sizeof(preload) || setenv("EASYLSP_NATIVE_CONFIG", config, 1) ||
        setenv("EASYLSP_PREVIOUS_LD_PRELOAD", old ? old : "", 1) || setenv("LD_PRELOAD", preload, 1)) { perror("launcher environment"); return 125; }
    execv(argv[4], argv + 4); perror("execv"); return errno == ENOENT ? 127 : 126;
}
