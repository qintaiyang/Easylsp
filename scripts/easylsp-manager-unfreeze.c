#define _GNU_SOURCE
/* Only the requested manager UID/PID: Binder thaw and ColorOS Hans UID entry. */
#include <errno.h>
#include <fcntl.h>
#include <linux/android/binder.h>
#include <linux/bpf.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2 && argc != 3) return 2;
    char *end;
    unsigned long parsed = strtoul(argv[1], &end, 10);
    if (*end || parsed < 10000 || parsed > UINT32_MAX) return 2;
    uint32_t uid = (uint32_t)parsed;
    const char *binder = "not-requested", *hans = "absent";
    int failed = 0;
    if (argc == 3) {
        unsigned long pid = strtoul(argv[2], &end, 10);
        if (*end || !pid || pid > INT32_MAX) return 2;
        char proc[80]; snprintf(proc, sizeof(proc), "/proc/%lu", pid);
        struct stat identity;
        if (stat(proc, &identity) || identity.st_uid != uid) return 3;
        int fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
        if (fd < 0) { binder = "error"; failed = 1; perror("Binder open"); }
        else {
            struct binder_freeze_info request = {.pid = (uint32_t)pid, .enable = 0, .timeout_ms = 0};
            if (!ioctl(fd, BINDER_FREEZE, &request)) binder = "unfrozen";
            else if (errno == ENOTTY) binder = "unsupported";
            else if (errno == EINVAL) binder = "unavailable";
            else if (errno == ESRCH) binder = "process-exited";
            else { binder = "error"; failed = 1; perror("Binder unfreeze"); }
            close(fd);
        }
    }
    union bpf_attr attr = {0};
    attr.pathname = (uintptr_t)"/sys/fs/bpf/map_oplus-netd_app_hans_limit_map";
    int fd = syscall(__NR_bpf, BPF_OBJ_GET, &attr, sizeof(attr));
    if (fd >= 0) {
        attr = (union bpf_attr){0}; attr.map_fd = fd; attr.key = (uintptr_t)&uid;
        if (!syscall(__NR_bpf, BPF_MAP_DELETE_ELEM, &attr, sizeof(attr)) || errno == ENOENT) hans = "cleared";
        else { hans = "error"; failed = 1; perror("Hans UID unfreeze"); }
        close(fd);
    } else if (errno != ENOENT) { hans = "error"; failed = 1; perror("Hans map open"); }
    printf("{\"binder\":\"%s\",\"hans\":\"%s\"}\n", binder, hans);
    return failed;
}
