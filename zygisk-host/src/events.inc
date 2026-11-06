static cJSON *process_events(unsigned target_uid, int include_isolated) {
    cJSON *result = cJSON_CreateObject(), *rows = cJSON_CreateArray(), *errors = cJSON_CreateArray();
    cJSON_AddItemToObject(result, "events", rows); cJSON_AddItemToObject(result, "errors", errors);
    FILE *table = fopen("/proc/net/unix", "re"); if (!table) die("read process endpoints");
    char line[2048];
    while (fgets(line, sizeof(line), table)) {
        char *name = strstr(line, "@easylsp-events."); if (!name) continue;
        name[strcspn(name, "\r\n")] = 0;
        unsigned uid, pid; unsigned long long ticks;
        int length = 0;
        if (sscanf(name, "@easylsp-events.%u.%u.%llu%n", &uid, &pid, &ticks, &length) != 3 || name[length] || (uid != target_uid && !(include_isolated && uid / 100000 == target_uid / 100000 && uid % 100000 >= 90000))) continue;
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        memcpy(address.sun_path + 1, name + 1, strlen(name + 1));
        int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0); if (fd < 0) die("event socket");
        eh_deadline(fd);
        struct ucred peer; socklen_t size = sizeof(peer);
        if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + strlen(name)) ||
            getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != uid || (unsigned)peer.pid != pid) {
            cJSON *error = cJSON_CreateObject(); cJSON_AddNumberToObject(error, "pid", pid); cJSON_AddNumberToObject(error, "errno", errno); cJSON_AddItemToArray(errors, error); close(fd); continue;
        }
        char event[2048]; ssize_t n = 0;
        unsigned received = 0;
        while (received++ < 128 && (n = recv(fd, event, sizeof(event) - 1, MSG_TRUNC)) > 0) {
            if ((size_t)n >= sizeof(event)) break;
            event[n] = 0; cJSON *row = cJSON_Parse(event);
            if (row && cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(row, "uid")) && cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(row, "pid")) && cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(row, "startTicks")) && cJSON_GetObjectItemCaseSensitive(row, "uid")->valueint == (int)uid &&
                cJSON_GetObjectItemCaseSensitive(row, "pid")->valueint == (int)pid && cJSON_GetObjectItemCaseSensitive(row, "startTicks")->valuedouble == (double)ticks) cJSON_AddItemToArray(rows, row);
            else cJSON_Delete(row);
        }
        if (n < 0) { cJSON *error = cJSON_CreateObject(); cJSON_AddNumberToObject(error, "pid", pid); cJSON_AddNumberToObject(error, "errno", errno); cJSON_AddItemToArray(errors, error); }
        close(fd);
    }
    fclose(table); return result;
}
