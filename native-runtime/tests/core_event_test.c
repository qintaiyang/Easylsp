#define EASYLSP_CORE_PROFILE 1
#include "../src/main/cpp/native_plugin.c"
#include <assert.h>
int sh_engine_initialize(void) { return 0; }
static unsigned received;
static void consume(void *user, const char *line) { (void)user; assert(strstr(line, "\"phase\":\"profile\"")); received++; }
int main(void) {
    struct plugin plugin = {0}; strcpy(plugin.plugin_id, "profile");
    for (unsigned i = 0; i < 10000; i++) event(&plugin, 0, "profile", 0, "short-lived evidence retained in logcat");
    uint64_t cursor = 0; sh_core_read_events_since(&cursor, consume, NULL); assert(received == EVENT_COUNT);
    sh_core_read_events_since(&cursor, consume, NULL); assert(received == EVENT_COUNT);
    event(&plugin, 0, "profile", 0, "incremental");
    sh_core_read_events_since(&cursor, consume, NULL); assert(received == EVENT_COUNT + 1);
    printf("desktop event sample: count=%" PRIu64 " format+ring mean=%" PRIu64 " ns log-stub mean=%" PRIu64 " ns; Android logcat cost requires device profiling\n",
        profile_events, profile_format_ns / profile_events, profile_log_ns / profile_events);
}
