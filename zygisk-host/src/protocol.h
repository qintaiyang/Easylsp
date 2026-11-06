#ifndef EASYLSP_HOST_PROTOCOL_H
#define EASYLSP_HOST_PROTOCOL_H
#include <stdint.h>
#include <stddef.h>
#include "cJSON.h"
#ifdef __cplusplus
extern "C" {
#endif
#define EH_PROTOCOL 1
#define EH_VERSION "0.9-dev1"
#define EH_RUNTIME "0.9-dev1-engine2.0.1-m7"
#define EH_DATA "/data/adb/easylsp"
#define EH_MODULE "easylsp_native_host"
#define EH_MAX_PLUGINS 16
#define EH_MAX_JSON (256 * 1024)
#define EH_TIMEOUT_MS 1500
struct eh_query { uint32_t protocol, uid; char process[256], app_data_dir[512]; };
struct eh_reply { uint32_t protocol, length, fd_count; int32_t result; };
int eh_boot_id(char out[64]);
int eh_identifier(const char *s);
int eh_package(const char *s);
const char *eh_string(const cJSON *object, const char *key);
int eh_validate_native(const cJSON *snapshot, char *error, size_t capacity);
int eh_validate(const cJSON *snapshot, char *error, size_t capacity);
int eh_matches(const cJSON *plugin, const char *package_name, const char *process);
int eh_read_all(int fd, void *buffer, size_t size);
int eh_write_all(int fd, const void *buffer, size_t size);
void eh_deadline(int fd);
int eh_send(int fd, const struct eh_reply *reply, const char *json, const int *fds);
int eh_receive(int fd, struct eh_reply *reply, char *json, int *fds);
cJSON *eh_read_snapshot(const char *path);
#ifdef __cplusplus
}
#endif
#endif
