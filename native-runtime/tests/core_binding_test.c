/* Runs real module matching/registration code; the Hook backend is a test double. */
#include "../src/main/cpp/native_plugin.c"
#include <assert.h>
int sh_engine_initialize(void) { return 0; }
static int replacement(int value) { return value; }
static int bind_new(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; bind_hook(data, info, SH_MODULE_BEFORE_CONSTRUCTORS); return 0;
}
int main(int argc, char **argv) {
    assert(argc == 3);
    assert(dlopen(argv[1], RTLD_NOW | RTLD_LOCAL));
    struct plugin plugin = {0};
    plugin.registering = 1; registration_owner = &plugin; atomic_store(&plugin.active, 1);
    strcpy(plugin.plugin_id, "fixture");
    instances[0] = &plugin; atomic_store(&instance_count, 1);
    sh_module_selector_v1 selector = { sizeof(selector), "libcandidate.so", NULL, "22222222" };
    void *original = NULL; sh_hook_id id;
    assert(hook_symbol_selected(&plugin, &selector, "fixture_value", SH_EXISTING_OR_FUTURE,
        (void *)replacement, &original, &id) == SH_WAITING);
    struct hook *hook = &plugin.hooks[0];
    assert(!hook->load_id && !hook->stub && !original);
    assert(dlopen(argv[2], RTLD_NOW | RTLD_LOCAL));
    dl_iterate_phdr(bind_new, hook);
    assert(hook->load_id && hook->stub && original && atomic_load(&hook->state) == SH_OK);
    assert(((int (*)(int))original)(7) == 8);
    void *retained = original;
    unhook_result = 1;
    cleanup_failed_instance(NULL, 0, &plugin);
    assert(!plugin.active && hook->stub && original == retained);
    unhook_result = 0;
    cleanup_failed_instance(NULL, 0, &plugin);
    assert(!hook->stub && original == retained);
    puts("Build ID rejected candidate stays waiting; later matching module binds; failed-init storage retained");
}
