#define _GNU_SOURCE
#include <sched.h>
#include <sys/mount.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: easylsp-mount-namespace command [args...]\n"); return 2; }
    if (unshare(CLONE_NEWNS) != 0) { perror("unshare mount namespace"); return 1; }
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) { perror("make mounts private"); return 1; }
    execvp(argv[1], argv + 1);
    perror("exec"); return 1;
}
