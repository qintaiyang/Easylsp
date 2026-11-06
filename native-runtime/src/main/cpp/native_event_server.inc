/* Read-only process event endpoint; root is the only permitted peer. */
static int event_listener = -1;
static void send_event(void *user, const char *line) {
    int fd = *(int *)user;
    send(fd, line, strlen(line), MSG_NOSIGNAL);
}
static void *event_server(void *unused) {
    (void)unused;
    for (;;) {
        int fd = accept4(event_listener, NULL, NULL, SOCK_CLOEXEC);
        if (fd < 0) { if (errno == EINTR) continue; break; }
        struct ucred peer; socklen_t size = sizeof(peer);
        struct timeval timeout = {.tv_sec = 1, .tv_usec = 500000};
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        if (!getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) && peer.uid == 0)
            sh_core_read_events(send_event, &fd);
        close(fd);
    }
    return NULL;
}
int sh_core_start_event_server(void) {
    if (event_listener >= 0) return SH_OK;
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
        "easylsp-events.%u.%u.%" PRIu64, getuid(), getpid(), start_ticks());
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    if (bind(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + (size_t)length) || listen(fd, 4)) {
        int code = errno; close(fd); return -code;
    }
    event_listener = fd;
    pthread_t thread;
    int result = pthread_create(&thread, NULL, event_server, NULL);
    if (result) { close(fd); event_listener = -1; return -result; }
    pthread_detach(thread); return SH_OK;
}
