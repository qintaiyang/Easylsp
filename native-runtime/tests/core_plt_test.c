/* Real GOT replacement / RELRO / registration. Inline backend remains a double. */
#include "../src/main/cpp/native_plugin.c"
#include <assert.h>
int sh_engine_initialize(void) { return 0; }
static int (*previous)(int);
static sh_hook_id current;
static int replacement(int value) { return previous(value) + 100; }
static int other(int value) { return value + 900; }
static int batch(const sh_host_v1 *api, void *instance, void *user) {
    sh_module_selector_v1 *selector = user;
    return api->hook_plt(instance,selector,"m7_value",SH_EXISTING_OR_FUTURE,(void *)replacement,(void **)&previous,&current);
}
int main(int argc, char **argv) {
    assert(argc==2);
    void *library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL); assert(library);
    int (*call)(int)=dlsym(library,"m7_call"); assert(call && call(7)==8);
    struct plugin plugin={0}; atomic_store(&plugin.active,1); strcpy(plugin.plugin_id,"plt-fixture");
    instances[0]=&plugin; instance_count=1;
    sh_module_selector_v1 selector={sizeof(selector),"libm7caller.so",NULL,NULL};
    assert(register_tasks(&plugin,batch,&selector)==SH_OK && call(7)==108);
    void *saved=NULL; sh_hook_id conflict;
    plugin.registering=1; registration_owner=&plugin;
    assert(hook_plt(&plugin,&selector,"m7_value",SH_EXISTING_OR_FUTURE,(void *)other,&saved,&conflict)==SH_CONFLICT);
    plugin.registering=0; registration_owner=NULL;
    struct hook *hook=&plugin.hooks[current-1];
    assert(!(hook->plt_protection & PROT_WRITE));
    assert(plt_replace(hook,(void *)replacement,(void *)other)==SH_OK && call(7)==907);
    assert(remove_hook(&plugin,current)==SH_CONFLICT && call(7)==907);
    assert(plt_replace(hook,(void *)other,(void *)replacement)==SH_OK);
    assert(remove_hook(&plugin,current)==SH_OK && call(7)==8 && previous(7)==8);
    assert(atomic_load(&hook->state)==SH_CANCELLED);
    module_callback_depth=1; assert(register_tasks(&plugin,batch,&selector)==SH_INVALID); module_callback_depth=0;
    assert(register_tasks(&plugin,batch,&selector)==SH_OK && call(7)==108);
    assert(remove_hook(&plugin,current)==SH_OK && call(7)==8);
    assert(dlclose(library)==0);
    puts("Real PLT caller-local GOT, original, RELRO restoration, conflict-preserving remove and runtime registration passed");
}
