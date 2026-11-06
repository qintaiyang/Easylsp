#include "easylsp_native_plugin.h"
#include <string>
#include <vector>
#include <stdexcept>
#include <typeinfo>
#include <errno.h>
static const sh_host_v1 *host;
static void *instance;
static int (*next_value)(int);
static sh_hook_id hook;
static int replacement(int value) {
    int result = next_value(value); int error = errno;
    host->hit(instance, hook); errno = error; return result + 3000;
}
struct Value { virtual ~Value() = default; };
struct Derived : Value {};
extern "C" EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    host = api; instance = context->instance;
    std::vector<std::string> words{"static", "STL"};
    try { throw std::runtime_error(words[0] + " " + words[1]); }
    catch (const std::exception &error) { host->log(instance, error.what()); }
    Derived value; Value *base = &value;
    if (!dynamic_cast<Derived *>(base) || typeid(*base) != typeid(Derived)) return SH_INVALID;
    sh_module_selector_v1 selector = {sizeof(selector), "libdemo_early_hook.so", nullptr, nullptr};
    sh_hook_options_v1 options = {sizeof(options), SH_CHAIN};
    int code = api->hook_symbol_options(instance, &selector, "easylsp_demo_early_value", SH_REQUIRE_BEFORE_CONSTRUCTORS,
        &options, (void *)replacement, (void **)&next_value, &hook);
    return code == SH_OK || code == SH_WAITING ? SH_OK : code;
}
