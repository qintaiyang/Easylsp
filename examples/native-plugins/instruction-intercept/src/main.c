#include "easylsp_native_plugin.h"
#include <string.h>
#define TARGET_ELF_VADDR 0x0
#define FLOAT_ELF_VADDR 0x0
static void integer_point(void *user, sh_arm64_registers_v1 *registers) {
    (void)user; registers->x[0] += 10;
}
static void float_point(void *user, sh_arm64_registers_v1 *registers) {
    (void)user; double value; memcpy(&value, registers->q[0], sizeof(value)); value += 5.0;
    memcpy(registers->q[0], &value, sizeof(value));
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->struct_size < sizeof(*api)) return SH_INVALID;
    sh_module_selector_v1 selector = {sizeof(selector), "libdemo_early_hook.so", 0, "REPLACE_FROM_ANALYSIS"};
    sh_hook_id integer_id, float_id;
    int code = api->intercept_elf_vaddr(context->instance, &selector, TARGET_ELF_VADDR, SH_REQUIRE_BEFORE_CONSTRUCTORS, 0, integer_point, 0, &integer_id);
    if (code != SH_OK && code != SH_WAITING) return code;
    code = api->intercept_elf_vaddr(context->instance, &selector, FLOAT_ELF_VADDR, SH_REQUIRE_BEFORE_CONSTRUCTORS, SH_SIMD_READ_WRITE, float_point, 0, &float_id);
    return code == SH_OK || code == SH_WAITING ? SH_OK : code;
}
