#ifndef EASYLSP_NATIVE_CORE_H
#define EASYLSP_NATIVE_CORE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Internal host protocol, independent of the public plugin ABI. Caller owns all
 * strings and the FD through this synchronous call; the core copies metadata. */
#define SH_CORE_PROTOCOL 1
typedef struct sh_core_plugin_v1 {
    uint32_t struct_size;
    int fd;
    const char *plugin_id, *build_id, *instance_id, *package_name, *process_name, *generation;
    uint64_t source_revision;
    const char *config_json;
    const char *release_id; /* Empty for local builds; not part of the public plugin ABI. */
} sh_core_plugin_v1;
typedef void (*sh_core_event_callback)(void *user, const char *json);
int sh_engine_initialize(void);
int sh_core_set_jni_env(void *environment);
int sh_core_claim(const char *backend);
int sh_core_load_plugin_fd(const sh_core_plugin_v1 *request);
void sh_core_read_events_since(uint64_t *cursor, sh_core_event_callback callback, void *user);
void sh_core_read_events(sh_core_event_callback callback, void *user);
int sh_core_start_event_server(void);
int sh_core_start_observation(const char *endpoint);
#ifdef __cplusplus
}
#endif
#endif
