/* Real Android liblog sample inside a dlopen constructor; no fake log implementation. */
#ifdef EASYLSP_PROFILE_LOADER
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    void (*report)(void) = dlsym(library, "report_event_sample");
    report();
    return 0;
}
#else
#define EASYLSP_CORE_PROFILE 1
#include "../src/main/cpp/native_plugin.c"
int sh_engine_initialize(void) { return 0; }
static uint64_t load_ns;
__attribute__((constructor)) static void sample_constructor(void) {
    struct plugin plugin = {0};
    strcpy(plugin.plugin_id, "optimization-profile");
    uint64_t began = profile_clock();
    for (unsigned i = 0; i < 1000; i++) event(&plugin, 0, "profile", 0, "constructor logcat evidence");
    load_ns = profile_clock() - began;
}
void report_event_sample(void) {
    printf("{\"events\":%" PRIu64 ",\"constructorNs\":%" PRIu64 ",\"formatRingMeanNs\":%" PRIu64 ",\"androidLogMeanNs\":%" PRIu64 "}\n",
           profile_events, load_ns, profile_format_ns / profile_events, profile_log_ns / profile_events);
}
#endif
