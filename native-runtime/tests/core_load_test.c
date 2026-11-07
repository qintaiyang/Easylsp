/* Exercise pre-entry load failures using real desktop dlopen/dlsym. */
#include "../src/main/cpp/native_plugin.c"
#include <assert.h>
#include <fcntl.h>
int sh_engine_initialize(void) { return 0; }
int main(int argc, char **argv) {
    assert(argc == 3);
    sh_core_plugin_v1 request = {
        .struct_size = sizeof(request), .fd = 99999,
        .plugin_id = "retry", .build_id = "build", .instance_id = "instance",
        .package_name = "review.target", .process_name = "review.target",
        .generation = "generation", .config_json = "{}"
    };
    for (unsigned i = 0; i < MAX_INSTANCES + 1; i++) {
        assert(sh_core_load_plugin_fd(&request) == SH_LOAD_FAILED);
        assert(instance_count == 0);
    }
    int missing = open(argv[1], O_RDONLY); assert(missing >= 0);
    request.fd = missing;
    for (unsigned i = 0; i < MAX_INSTANCES + 1; i++) {
        assert(sh_core_load_plugin_fd(&request) == SH_ENTRY_MISSING);
        assert(instance_count == 0);
    }
    int valid = open(argv[2], O_RDONLY); assert(valid >= 0);
    request.fd = valid;
    assert(sh_core_load_plugin_fd(&request) == SH_OK);
    assert(instance_count == 1 && instances[0]->active);
    assert(sh_core_load_plugin_fd(&request) == SH_INVALID);
    close(missing); close(valid);
    puts("Repeated load/entry failures do not consume slots; same plugin can subsequently load");
}
