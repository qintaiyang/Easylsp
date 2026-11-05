#include "easylsp_native_plugin.h"

int easylsp_plugin_init_v1(const sh_host_v1 *host, const sh_plugin_context_v1 *context) {
    if (host->abi_version != EASYLSP_NATIVE_ABI_V1 || host->struct_size < sizeof(sh_host_v1)) return SH_INVALID;
    host->log(context->instance, "plugin initialized; register hooks using confirmed function signatures");
    return SH_OK;
}
