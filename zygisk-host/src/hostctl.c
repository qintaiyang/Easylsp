#define _GNU_SOURCE
#include "protocol.h"
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/xattr.h>
#endif

static const char *data_dir = EH_DATA;
static void die(const char *step) { fprintf(stderr, "%s: %s\n", step, strerror(errno)); exit(1); }
static void directory(const char *path) {
    if (mkdir(path, 0700) && errno != EEXIST) die(path);
    struct stat s; if (lstat(path, &s) || !S_ISDIR(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0022)) { errno = EACCES; die(path); }
}
static void write_file(int fd, const char *bytes, size_t length) {
    while (length) { ssize_t n = write(fd, bytes, length); if (n < 0 && errno == EINTR) continue; if (n <= 0) die("write"); bytes += n; length -= (size_t)n; }
}
static void atomic_json(const char *path, const cJSON *json) {
    char temporary[1200]; snprintf(temporary, sizeof(temporary), "%s.%u.tmp", path, (unsigned)getpid());
    int fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600); if (fd < 0) die("create snapshot");
    char *bytes = cJSON_PrintUnformatted(json); if (!bytes) { errno = ENOMEM; die("serialize"); }
    write_file(fd, bytes, strlen(bytes)); free(bytes); if (fsync(fd)) die("fsync snapshot"); close(fd);
    if (rename(temporary, path)) die("commit snapshot");
    char parent[1200]; strcpy(parent, path); *strrchr(parent, '/') = 0;
    fd = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC); if (fd < 0 || fsync(fd)) die("sync deployment directory"); close(fd);
}
static void artifact(const cJSON *p, const char *source_dir) {
    char parent[1024], destination[1200], source[4096], temporary[1250];
    const char *id = eh_string(p, "localPluginId"), *build = eh_string(p, "buildId");
    snprintf(parent, sizeof(parent), "%s/artifacts/%s", data_dir, id); directory(parent);
    snprintf(destination, sizeof(destination), "%s/%s", parent, build);
    struct stat s;
    if (!lstat(destination, &s)) {
        if (!S_ISDIR(s.st_mode) || s.st_uid != getuid() || (s.st_mode & 0022)) { errno = EACCES; die("existing immutable artifact"); }
        return; /* buildId is immutable. Re-publication reuses it without replacing bytes. */
    }
    if (errno != ENOENT) die("artifact stat");
    snprintf(source, sizeof(source), "%s/%s/%s/plugin.so", source_dir, id, build);
    int input = open(source, O_RDONLY | O_CLOEXEC | O_NOFOLLOW); if (input < 0) die("open source artifact");
    Elf64_Ehdr elf;
    if (fstat(input, &s) || !S_ISREG(s.st_mode) || s.st_size > 16 * 1024 * 1024 || read(input, &elf, sizeof(elf)) != sizeof(elf) ||
        memcmp(elf.e_ident, ELFMAG, SELFMAG) || elf.e_ident[EI_CLASS] != ELFCLASS64 || elf.e_ident[EI_DATA] != ELFDATA2LSB || elf.e_type != ET_DYN || elf.e_machine != EM_AARCH64) {
        errno = EINVAL; die("expected arm64 Android shared library (max 16 MiB)");
    }
    if (lseek(input, 0, SEEK_SET) < 0) die("rewind artifact");
    snprintf(temporary, sizeof(temporary), "%s/.%s-%u", parent, build, (unsigned)getpid()); directory(temporary);
    char output_path[1300]; snprintf(output_path, sizeof(output_path), "%s/plugin.so", temporary);
    int output = open(output_path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600); if (output < 0) die("create artifact");
    char buffer[65536]; ssize_t n;
    while ((n = read(input, buffer, sizeof(buffer))) > 0) write_file(output, buffer, (size_t)n);
    if (n < 0) die("read artifact");
    close(input);
#ifdef __ANDROID__
    /* The root directory stays private; the received FD must be mappable by zygote/app domains. */
    const char context[] = "u:object_r:system_file:s0";
    if (fsetxattr(output, "security.selinux", context, sizeof(context), 0)) die("label executable artifact");
#endif
    if (fsync(output)) die("fsync artifact");
    close(output);
    if (rename(temporary, destination)) die("commit artifact");
}
static void snapshot_path(char *out, size_t size, int uid, const char *package_name) { snprintf(out, size, "%s/deployments/%u/%s.json", data_dir, uid, package_name); }
static cJSON *publish(const char *snapshot_file, const char *source_dir) {
    cJSON *s = eh_read_snapshot(snapshot_file); char error[256];
    if (eh_validate(s, error, sizeof(error))) { fprintf(stderr, "snapshot: %s\n", error); exit(1); }
    int uid = cJSON_GetObjectItemCaseSensitive(s, "uid")->valueint;
    char path[1200], parent[1100];
    snprintf(parent, sizeof(parent), "%s/deployments/%u", data_dir, uid); directory(parent);
    snapshot_path(path, sizeof(path), uid, eh_string(s, "packageName"));
    cJSON *current = eh_read_snapshot(path);
    if (current) {
        /* Repair the receipt after a crash between current rename and receipt rename,
         * before another publish can replace that current snapshot. */
        char receipt[1200]; snprintf(receipt, sizeof(receipt), "%s/history/%s.json", data_dir, eh_string(current, "publishRequestId"));
        atomic_json(receipt, current);
        if (!strcmp(eh_string(current, "publishRequestId"), eh_string(s, "publishRequestId"))) { cJSON_Delete(s); return current; }
    }
    cJSON_Delete(current);
    /* A completed request is immutable even if a newer request is now current. */
    char history[1200]; snprintf(history, sizeof(history), "%s/history/%s.json", data_dir, eh_string(s, "publishRequestId"));
    cJSON *completed = eh_read_snapshot(history);
    if (completed) { cJSON_Delete(s); return completed; }
    const cJSON *p; cJSON_ArrayForEach(p, cJSON_GetObjectItemCaseSensitive(s, "orderedPlugins")) artifact(p, source_dir);
    atomic_json(path, s);
    atomic_json(history, s);
    return s;
}
static void disable(int uid, const char *package_name) {
    char path[1200]; snapshot_path(path, sizeof(path), uid, package_name);
    cJSON *current = eh_read_snapshot(path);
    if (current) {
        char receipt[1200]; snprintf(receipt, sizeof(receipt), "%s/history/%s.json", data_dir, eh_string(current, "publishRequestId"));
        atomic_json(receipt, current); cJSON_Delete(current);
    }
    if (unlink(path)) { if (errno == ENOENT) return; die("disable"); }
    *strrchr(path, '/') = 0;
    int parent = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (parent < 0 || fsync(parent)) die("sync disabled deployment");
    close(parent);
}
static cJSON *snapshots(void) {
    cJSON *rows = cJSON_CreateArray();
    char path[1200]; snprintf(path, sizeof(path), "%s/deployments", data_dir);
    DIR *users = opendir(path); if (!users) die("deployments");
    struct dirent *user;
    while ((user = readdir(users))) {
        char *end; long uid = strtol(user->d_name, &end, 10); if (*end || uid < 10000 || uid > 1999999999) continue;
        snprintf(path, sizeof(path), "%s/deployments/%s", data_dir, user->d_name);
        DIR *dir = opendir(path); if (!dir) die("deployment user");
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            size_t n = strlen(entry->d_name); if (n < 6 || strcmp(entry->d_name + n - 5, ".json")) continue;
            snprintf(path, sizeof(path), "%s/deployments/%s/%s", data_dir, user->d_name, entry->d_name);
            cJSON *s = eh_read_snapshot(path); if (!s) { errno = EINVAL; die("read current snapshot"); }
            cJSON_AddItemToArray(rows, s);
        }
        closedir(dir);
    }
    closedir(users); return rows;
}
static cJSON *artifacts(int collect) {
    /* All retained request receipts are rollback references. gc removes only orphans. */
    cJSON *references = snapshots();
    char path[1500]; snprintf(path, sizeof(path), "%s/history", data_dir);
    DIR *history = opendir(path); if (!history) die("history"); struct dirent *entry;
    while ((entry = readdir(history))) {
        size_t n = strlen(entry->d_name); if (n < 6 || strcmp(entry->d_name + n - 5, ".json")) continue;
        snprintf(path, sizeof(path), "%s/history/%s", data_dir, entry->d_name);
        cJSON *s = eh_read_snapshot(path); if (!s) { errno = EINVAL; die("read retained snapshot"); } cJSON_AddItemToArray(references, s);
    }
    closedir(history);
    snprintf(path,sizeof(path),"%s/native-targets",data_dir); DIR *native = opendir(path);
    if (native) {
        while ((entry = readdir(native))) {
            size_t n = strlen(entry->d_name); if (n < 6 || strcmp(entry->d_name+n-5,".json")) continue;
            snprintf(path,sizeof(path),"%s/native-targets/%s",data_dir,entry->d_name);
            cJSON *target = eh_read_snapshot(path); if (!target) { errno = EINVAL; die("read Native target"); }
            cJSON_AddItemToObject(target,"orderedPlugins",cJSON_Duplicate(cJSON_GetObjectItemCaseSensitive(target,"plugins"),1)); cJSON_AddItemToArray(references,target);
        }
        closedir(native);
    }
    cJSON *result = cJSON_CreateArray(); snprintf(path, sizeof(path), "%s/artifacts", data_dir);
    DIR *plugins = opendir(path); if (!plugins) die("artifacts"); struct dirent *plugin;
    while ((plugin = readdir(plugins))) {
        if (!eh_identifier(plugin->d_name)) continue;
        snprintf(path, sizeof(path), "%s/artifacts/%s", data_dir, plugin->d_name);
        DIR *builds = opendir(path); if (!builds) die("builds");
        while ((entry = readdir(builds))) {
            if (!eh_identifier(entry->d_name)) continue;
            int retained = 0; const cJSON *s, *p;
            cJSON_ArrayForEach(s, references) cJSON_ArrayForEach(p, cJSON_GetObjectItemCaseSensitive(s, "orderedPlugins"))
                if (!strcmp(eh_string(p, "localPluginId"), plugin->d_name) && !strcmp(eh_string(p, "buildId"), entry->d_name)) retained = 1;
            snprintf(path, sizeof(path), "%s/artifacts/%s/%s/plugin.so", data_dir, plugin->d_name, entry->d_name);
            cJSON *row = cJSON_CreateObject(); cJSON_AddStringToObject(row, "localPluginId", plugin->d_name); cJSON_AddStringToObject(row, "buildId", entry->d_name); cJSON_AddBoolToObject(row, "retained", retained);
            if (collect && !retained) { if (unlink(path)) die("gc artifact"); *strrchr(path, '/') = 0; if (rmdir(path)) die("gc build"); cJSON_AddBoolToObject(row, "removed", 1); }
            cJSON_AddItemToArray(result, row);
        }
        closedir(builds);
    }
    closedir(plugins); cJSON_Delete(references); return result;
}
static cJSON *prune_history(void) {
    cJSON *current = snapshots();
    char path[1500]; snprintf(path, sizeof(path), "%s/history", data_dir);
    DIR *history = opendir(path); if (!history) die("history");
    struct dirent *entry; unsigned removed = 0;
    while ((entry = readdir(history))) {
        size_t length = strlen(entry->d_name);
        if (length < 6 || strcmp(entry->d_name + length - 5, ".json")) continue;
        char request[64]; if (length - 5 >= sizeof(request)) continue;
        memcpy(request, entry->d_name, length - 5); request[length - 5] = 0;
        int retained = 0; const cJSON *row;
        cJSON_ArrayForEach(row, current) if (!strcmp(eh_string(row, "publishRequestId"), request)) retained = 1;
        if (retained) continue;
        snprintf(path, sizeof(path), "%s/history/%s", data_dir, entry->d_name);
        if (unlink(path)) die("remove old rollback receipt");
        removed++;
    }
    closedir(history); cJSON_Delete(current);
    cJSON *result = cJSON_CreateObject(); cJSON_AddNumberToObject(result, "removedReceipts", removed); return result;
}
#include "events.inc"
static cJSON *native_targets(void) {
    char path[1400]; snprintf(path,sizeof(path),"%s/native-targets",data_dir);
    cJSON *rows = cJSON_CreateArray(); DIR *dir = opendir(path); if (!dir) return rows;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        size_t n = strlen(entry->d_name); if (n < 6 || strcmp(entry->d_name+n-5,".json")) continue;
        snprintf(path,sizeof(path),"%s/native-targets/%s",data_dir,entry->d_name);
        cJSON *row = eh_read_snapshot(path); if (row) cJSON_AddItemToArray(rows,row);
    }
    closedir(dir); return rows;
}
static void next_route(const char *executable, const char *kind, int remove) {
    if (executable[0] != '/' || strlen(executable) >= 1024 || strpbrk(executable," \t\r\n") || (strcmp(kind,"native") && strcmp(kind,"hyos"))) { errno = EINVAL; die("Next route arguments"); }
    const char *file = "/data/adb/modules/" EH_MODULE "/zn_modules.txt";
    char temporary[256]; snprintf(temporary,sizeof(temporary),"%s.%u.tmp",file,getpid());
    int fd = open(temporary,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0644); if (fd < 0) die("Next route create");
    FILE *previous = fopen(file,"re"); char line[2048], prefix[1200];
    snprintf(prefix,sizeof(prefix),"path=%s ",executable);
    if (previous) { while (fgets(line,sizeof(line),previous)) if (strncmp(line,prefix,strlen(prefix))) write_file(fd,line,strlen(line)); fclose(previous); }
    if (!remove) { int n = snprintf(line,sizeof(line),"path=%s lib/arm64-v8a/libeasylsp-%s.so\n",executable,!strcmp(kind,"hyos") ? "hyos" : "next"); write_file(fd,line,(size_t)n); }
    if (fsync(fd)) die("Next route sync");
    close(fd); if (rename(temporary,file)) die("Next route commit");
}
int main(int argc, char **argv) {
    if (argc > 3 && !strcmp(argv[1], "--data-dir")) { data_dir = argv[2]; argc -= 2; argv += 2; }
    if (getuid() != 0) { fprintf(stderr, "hostctl requires root\n"); return 1; }
    if (argc < 2) { fprintf(stderr, "hostctl [--data-dir DIR] publish SNAPSHOT ARTIFACT_DIR | disable UID PACKAGE | disable-all | status | list-artifacts | gc | native-publish CONFIG ARTIFACT_DIR | native-disable TARGET_ID | next-route EXECUTABLE native|hyos | next-unroute EXECUTABLE\n"); return 2; }
    umask(0077); directory(data_dir);
    char path[1200];
    const char *dirs[] = {"artifacts", "deployments", "history", "diagnostics", "native-targets"};
    for (unsigned i = 0; i < 5; i++) { snprintf(path, sizeof(path), "%s/%s", data_dir, dirs[i]); directory(path); }
    snprintf(path, sizeof(path), "%s/.lock", data_dir);
    int lock = open(path, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600); if (lock < 0 || flock(lock, LOCK_EX)) die("lock");
    cJSON *result = NULL;
    if (argc == 4 && !strcmp(argv[1], "next-route")) {
        next_route(argv[2],argv[3],0);
    } else if (argc == 3 && !strcmp(argv[1], "next-unroute")) {
        next_route(argv[2],"native",1);
    } else if (argc == 4 && !strcmp(argv[1], "native-publish")) {
        result = eh_read_snapshot(argv[2]); char error[256];
        if (eh_validate_native(result,error,sizeof(error))) { fprintf(stderr,"Native target: %s\n",error); return 2; }
        const cJSON *p; cJSON_ArrayForEach(p,cJSON_GetObjectItemCaseSensitive(result,"plugins")) artifact(p,argv[3]);
        snprintf(path,sizeof(path),"%s/native-targets/%s.json",data_dir,eh_string(result,"targetId")); atomic_json(path,result);
    } else if (argc == 3 && !strcmp(argv[1], "native-disable")) {
        if (!eh_identifier(argv[2])) return 2;
        snprintf(path,sizeof(path),"%s/native-targets/%s.json",data_dir,argv[2]);
        if (unlink(path) && errno != ENOENT) die("disable Native entry");
        result = cJSON_CreateObject(); cJSON_AddStringToObject(result,"status","DISABLED_PENDING_RESTART");
    } else if (argc == 3 && !strcmp(argv[1], "diagnostic")) {
        result = eh_read_snapshot(argv[2]); char error[256];
        if (eh_validate(result, error, sizeof(error)) || !cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(result, "diagnostic"))) { fprintf(stderr, "invalid diagnostic snapshot\n"); return 2; }
        snprintf(path, sizeof(path), "%s/diagnostics/%u", data_dir, cJSON_GetObjectItemCaseSensitive(result, "uid")->valueint); directory(path);
        snprintf(path, sizeof(path), "%s/diagnostics/%u/%s.json", data_dir, cJSON_GetObjectItemCaseSensitive(result, "uid")->valueint, eh_string(result, "packageName"));
        char boot[64]; if (eh_boot_id(boot)) die("read diagnostic boot identity"); cJSON_AddStringToObject(result, "bootId", boot);
        atomic_json(path, result);
    } else if (argc == 5 && !strcmp(argv[1], "clear-diagnostic")) {
        char *end; long uid = strtol(argv[2], &end, 10);
        if (*end || uid < 10000 || uid > 1999999999 || !eh_package(argv[3]) || !eh_identifier(argv[4])) return 2;
        snprintf(path, sizeof(path), "%s/diagnostics/%ld/%s.json", data_dir, uid, argv[3]);
        cJSON *current = eh_read_snapshot(path);
        const char *run = eh_string(cJSON_GetObjectItemCaseSensitive(current, "diagnostic"), "diagnosticRunId");
        if (run && !strcmp(run, argv[4]) && unlink(path)) die("clear diagnostic");
        cJSON_Delete(current);
    } else if (argc == 4 && !strcmp(argv[1], "publish")) result = publish(argv[2], argv[3]);
    else if (argc == 4 && !strcmp(argv[1], "disable")) {
        char *end; long uid = strtol(argv[2], &end, 10);
        if (*end || uid < 10000 || uid > 1999999999 || !eh_package(argv[3])) { fprintf(stderr, "invalid target identity\n"); return 2; }
        disable((int)uid, argv[3]);
    } else if (argc == 2 && !strcmp(argv[1], "disable-all")) {
        cJSON *rows = snapshots(), *s;
        cJSON_ArrayForEach(s, rows) { disable(cJSON_GetObjectItemCaseSensitive(s, "uid")->valueint, eh_string(s, "packageName")); }
        cJSON_Delete(rows);
    } else if (argc == 2 && !strcmp(argv[1], "status")) {
        result = cJSON_CreateObject(); cJSON_AddNumberToObject(result, "hostProtocolVersion", EH_PROTOCOL); cJSON_AddStringToObject(result, "installedVersion", EH_VERSION);
        cJSON_AddStringToObject(result, "installedRuntime", EH_RUNTIME);
        cJSON *capabilities = cJSON_AddArrayToObject(result, "capabilities");
        const char *supported[] = {"native.publish", "native.events", "native.diagnostic", "native.chain", "native.jni-register", "native.intercept", "native.plt", "native.runtime-register", "native.multiuser", "native.isolated", "native.next-entry"};
        for (unsigned i = 0; i < sizeof(supported) / sizeof(supported[0]); ++i)
            cJSON_AddItemToArray(capabilities, cJSON_CreateString(supported[i]));
        cJSON *providers = cJSON_AddArrayToObject(result, "providerCandidates");
        const char *provider_ids[] = {"zygisksu", "zygisk_next", "rezygisk", "neozygisk"};
        for (unsigned i = 0; i < sizeof(provider_ids) / sizeof(provider_ids[0]); i++) {
            char module[256]; snprintf(module, sizeof(module), "/data/adb/modules/%s/module.prop", provider_ids[i]);
            FILE *file = fopen(module, "re"); if (!file) continue;
            cJSON *candidate = cJSON_CreateObject(); cJSON_AddStringToObject(candidate, "id", provider_ids[i]);
            char line[256]; while (fgets(line, sizeof(line), file)) if (!strncmp(line, "version=", 8)) {
                line[strcspn(line, "\r\n")] = 0; cJSON_AddStringToObject(candidate, "version", line + 8);
            }
            fclose(file); snprintf(module, sizeof(module), "/data/adb/modules/%s/disable", provider_ids[i]);
            cJSON_AddBoolToObject(candidate, "enabled", access(module, F_OK) != 0); cJSON_AddItemToArray(providers, candidate);
        }
        snprintf(path, sizeof(path), "%s/runtime.json", data_dir);
        cJSON *runtime = eh_read_snapshot(path);
        char boot[64]; int current_boot = !eh_boot_id(boot);
        const char *status = "UNKNOWN_UNTIL_PROCESS_EVIDENCE";
        if (runtime && eh_string(runtime, "bootId") && current_boot && !strcmp(eh_string(runtime, "bootId"), boot)) {
            const char *version = eh_string(runtime, "runtimeVersion");
            status = version && !strcmp(version, EH_RUNTIME) ? "COMPANION_LOADED" : "PENDING_REBOOT";
            cJSON_AddItemToObject(result, "runtime", runtime); runtime = NULL;
        }
        cJSON_Delete(runtime);
        cJSON_AddStringToObject(result, "runtimeStatus", status); cJSON_AddItemToObject(result, "deployments", snapshots());
        cJSON_AddItemToObject(result, "nativeTargets", native_targets());
    } else if (argc == 2 && !strcmp(argv[1], "prune-history")) result = prune_history();
    else if ((argc == 3 || (argc == 4 && !strcmp(argv[3], "isolated"))) && !strcmp(argv[1], "events")) {
        char *end; unsigned long uid = strtoul(argv[2], &end, 10);
        if (*end || uid > 1999999999) { fprintf(stderr, "invalid event UID\n"); return 2; }
        result = process_events((unsigned)uid, argc == 4);
    } else if (argc == 2 && (!strcmp(argv[1], "list-artifacts") || !strcmp(argv[1], "gc"))) result = artifacts(!strcmp(argv[1], "gc"));
    else { fprintf(stderr, "unknown command or invalid arguments\n"); return 2; }
    if (!result) result = cJSON_CreateObject();
    char *json = cJSON_PrintUnformatted(result); puts(json); free(json); cJSON_Delete(result); close(lock); return 0;
}
