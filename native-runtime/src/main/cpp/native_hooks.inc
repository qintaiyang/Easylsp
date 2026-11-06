/* Called under the Android linker lock (dl_iterate_phdr or linker callbacks).
 * Registrations publish fully initialized slots with atomic counts. No Java or I/O. */
struct module_identity { const ElfW(Phdr) *phdr; uintptr_t bias; uint64_t id; };
static struct module_identity module_ids[128];
static uint64_t next_module_id;
static pthread_once_t hooks_once = PTHREAD_ONCE_INIT;
static int hooks_result;
struct diagnostic_owner { uintptr_t address; const char *name; };
static struct diagnostic_owner diagnostic_owners[5];
static unsigned diagnostic_count;

static int selector_copy(struct selector *out, const sh_module_selector_v1 *s) {
    if (!s || s->struct_size < sizeof(*s) || ((!s->name || !*s->name) && (!s->path || !*s->path))) return SH_INVALID;
    const char *name = s->name ? s->name : "", *path = s->path ? s->path : "", *id = s->elf_build_id ? s->elf_build_id : "";
    if (strlen(name) >= sizeof(out->name) || strlen(path) >= sizeof(out->path) || strlen(id) >= sizeof(out->build_id)) return SH_INVALID;
    for (const char *c = id; *c; c++) if (!((*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f'))) return SH_INVALID;
    if (strlen(id) % 2) return SH_INVALID;
    strcpy(out->name, name); strcpy(out->path, path); strcpy(out->build_id, id); return 0;
}
static int matches_path(const struct selector *s, const struct dl_phdr_info *info) {
    const char *base = strrchr(info->dlpi_name, '/'); base = base ? base + 1 : info->dlpi_name;
    return (!s->name[0] || strcmp(s->name, base) == 0) && (!s->path[0] || strcmp(s->path, info->dlpi_name) == 0);
}
static int in_load(const struct dl_phdr_info *info, uintptr_t vaddr, size_t length, unsigned flags) {
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type == PT_LOAD && (p->p_flags & flags) == flags && vaddr >= p->p_vaddr &&
            vaddr - p->p_vaddr <= p->p_memsz && length <= p->p_memsz - (vaddr - p->p_vaddr)) return 1;
    }
    return 0;
}
static void module_build_id(const struct dl_phdr_info *info, char out[129]) {
    out[0] = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        if (p->p_type != PT_NOTE || !in_load(info, p->p_vaddr, p->p_memsz, PF_R)) continue;
        const unsigned char *cursor = (const unsigned char *)(info->dlpi_addr + p->p_vaddr), *end = cursor + p->p_memsz;
        while ((size_t)(end - cursor) >= sizeof(ElfW(Nhdr))) {
            ElfW(Nhdr) note; memcpy(&note, cursor, sizeof(note)); cursor += sizeof(note);
            size_t names = ((size_t)note.n_namesz + 3) & ~(size_t)3, desc = ((size_t)note.n_descsz + 3) & ~(size_t)3;
            if (names > (size_t)(end - cursor) || desc > (size_t)(end - cursor) - names) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && !memcmp(cursor, "GNU\0", 4) && note.n_descsz <= 64) {
                static const char hex[] = "0123456789abcdef";
                for (uint32_t j = 0; j < note.n_descsz; j++) { unsigned char byte = cursor[names + j]; out[j * 2] = hex[byte >> 4]; out[j * 2 + 1] = hex[byte & 15]; }
                out[note.n_descsz * 2] = 0; return;
            }
            cursor += names + desc;
        }
    }
}
static uint64_t module_id(const struct dl_phdr_info *info) {
    for (unsigned i = 0; i < 128; i++) if (module_ids[i].phdr == info->dlpi_phdr && module_ids[i].bias == info->dlpi_addr) return module_ids[i].id;
    for (unsigned i = 0; i < 128; i++) if (!module_ids[i].phdr) {
        module_ids[i] = (struct module_identity){info->dlpi_phdr, info->dlpi_addr, ++next_module_id}; return next_module_id;
    }
    return 0;
}
struct match_query { const struct selector *selector; unsigned count; };
static int count_selected(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; struct match_query *q = data;
    if (matches_path(q->selector, info)) {
        char id[129]; module_build_id(info, id);
        if (!q->selector->build_id[0] || !strcmp(q->selector->build_id, id)) q->count++;
    }
    return 0;
}
static const char *address_owner(uintptr_t address, struct hook *self) {
    for (unsigned i = 0; i < instance_count; i++) for (unsigned j = 0; j < instances[i]->hook_count; j++) {
        struct hook *h = &instances[i]->hooks[j];
        if (h != self && h->stub && h->address == address && (!self || self->mode != h->mode || h->mode == SH_EXCLUSIVE)) return h->owner->plugin_id;
    }
    for (unsigned i = 0; i < diagnostic_count; i++) if (diagnostic_owners[i].address == address) return diagnostic_owners[i].name;
    return NULL;
}
/* Diagnostic probes use the same UNIQUE ownership registry. Their mode remains opt-in. */
const char *sh_native_address_owner(void *address) { return address_owner((uintptr_t)address, NULL); }
void sh_native_record_probe(void *address, const char *name) {
    if (diagnostic_count < 5) diagnostic_owners[diagnostic_count++] = (struct diagnostic_owner){(uintptr_t)address, name};
}
static void intercept_dispatch(shadowhook_cpu_context_t *cpu, void *user) {
    struct hook *h = user;
    sh_arm64_registers_v1 view = {.struct_size = sizeof(view), .flags = h->intercept_flags,
        .sp = cpu->sp, .pc = cpu->pc, .pstate = cpu->pstate};
    memcpy(view.x, cpu->regs, sizeof(view.x));
    if (h->intercept_flags & SH_SIMD_READ_WRITE) {
        memcpy(view.q, cpu->vregs, sizeof(view.q)); view.fpsr = cpu->fpsr; view.fpcr = cpu->fpcr;
    }
    int saved_errno = errno;
    module_callback_depth++; h->interceptor(h->interceptor_user, &view); module_callback_depth--;
    memcpy(cpu->regs, view.x, sizeof(view.x));
    if (h->intercept_flags & SH_SIMD_READ_WRITE) {
        memcpy(cpu->vregs, view.q, sizeof(view.q)); cpu->fpsr = view.fpsr; cpu->fpcr = view.fpcr;
    }
    errno = saved_errno;
}
#include "native_plt.inc"
static int install_at(struct hook *h, uintptr_t address) {
    const char *owner = address_owner(address, h);
    if (owner) return SH_CONFLICT;
    h->address = address;
    if (h->mode == 2) h->stub = shadowhook_intercept_instr_addr((void *)address, intercept_dispatch, h,
        h->intercept_flags & SH_SIMD_READ_WRITE ? SHADOWHOOK_INTERCEPT_WITH_FPSIMD_READ_WRITE : SHADOWHOOK_INTERCEPT_DEFAULT);
    else {
        uint32_t flags = h->mode == SH_CHAIN ? SHADOWHOOK_HOOK_WITH_MULTI_MODE : SHADOWHOOK_HOOK_WITH_UNIQUE_MODE;
        h->stub = h->symbol[0] ? shadowhook_hook_sym_addr_2((void *)address, h->replacement, h->original, flags)
            : shadowhook_hook_func_addr_2((void *)address, h->replacement, h->original, flags);
    }
    return h->stub ? SH_OK : shadowhook_get_errno();
}
static int detach_hook(struct hook *h) {
    if (h->mode == 3) return detach_plt(h);
    if (h->jni_class_name[0]) return SH_OK; /* UnregisterNatives/VM own this binding. */
    return !h->stub ? 0 : h->mode == 2 ? shadowhook_unintercept(h->stub) : shadowhook_unhook(h->stub);
}
static void bind_hook(struct hook *h, struct dl_phdr_info *info, uint32_t phase) {
    if (h->cancelled || h->jni_class_name[0]) return; /* JNI requests bind only to observed successful registrations. */
    if (!matches_path(&h->selector, info) || h->load_id) return;
    char build[129], detail[512]; module_build_id(info, build);
    int code = 0;
    if (h->selector.build_id[0] && strcmp(build, h->selector.build_id)) {
        atomic_store(&h->state, SH_WAITING);
        event(h->owner, h->id, "hook_version_mismatch", SH_VERSION_MISMATCH, info->dlpi_name);
        return; /* A rejected candidate must not claim load_id: a later library can match. */
    }
    struct match_query q = {&h->selector, 0}; dl_iterate_phdr(count_selected, &q);
    if (!code && q.count > 1) code = SH_AMBIGUOUS;
    if (!code && phase == SH_MODULE_EXISTING && h->phase == SH_REQUIRE_BEFORE_CONSTRUCTORS) code = SH_MISSED_STAGE;
    h->load_id = module_id(info); h->load_bias = info->dlpi_addr;
    if (!h->load_id) code = SH_LIMIT;
    uintptr_t address = 0;
    if (!code && h->mode == 3) {
        code = bind_plt(h, info);
        address = h->address;
    } else if (!code && h->symbol[0]) {
        /* ShadowHook's ELF reader does not load a library or execute constructors. */
        void *handle = shadowhook_dlopen(info->dlpi_name);
        if (handle) { address = (uintptr_t)shadowhook_dlsym(handle, h->symbol); shadowhook_dlclose(handle); }
        if (!address) code = SHADOWHOOK_ERRNO_HOOK_DLSYM;
    } else if (!code) {
        if ((h->vaddr & 3) || !in_load(info, h->vaddr, 4, PF_X)) code = SH_INVALID_ADDRESS;
        else address = info->dlpi_addr + h->vaddr;
    }
    const char *owner = address ? address_owner(address, h) : NULL;
    if (owner) code = SH_CONFLICT;
    if (!code && h->mode != 3) code = install_at(h, address);
    atomic_store(&h->state, code);
    snprintf(detail, sizeof(detail), "module=%.200s loadId=%" PRIu64 " bias=0x%" PRIxPTR " address=0x%" PRIxPTR " buildId=%.128s%s%.64s",
        info->dlpi_name, h->load_id, h->load_bias, address, build, owner ? " owner=" : "", owner ? owner : "");
    event(h->owner, h->id, owner ? "hook_conflict" : code ? "hook_failed" : phase == SH_MODULE_EXISTING ? "hook_installed_existing" : "hook_installed", code, detail);
}
struct hook_query { struct hook *hook; };
static int bind_existing(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; bind_hook(((struct hook_query *)data)->hook, info, SH_MODULE_EXISTING); return 0;
}
static int register_hook(void *instance, const sh_module_selector_v1 *selector, const char *symbol, uintptr_t vaddr,
                         uint32_t phase, void *replacement, void **original, sh_hook_id *id, uint32_t mode, sh_interceptor_v1 interceptor, void *user, uint32_t flags) {
    struct plugin *p = instance;
    if (!p->active || !p->registering || registration_owner != p || module_callback_depth || (mode != 2 && (!replacement || !original)) || !id || phase > SH_REQUIRE_BEFORE_CONSTRUCTORS || (symbol && (!*symbol || strlen(symbol) >= 256))) return SH_INVALID;
    unsigned index = p->hook_count; if (index == MAX_HOOKS) return SH_LIMIT;
    struct hook *h = &p->hooks[index];
    int code = selector_copy(&h->selector, selector); if (code) return code;
    if (!symbol && !h->selector.build_id[0]) return SH_INVALID;
    h->mode = mode; h->interceptor = interceptor; h->interceptor_user = user; h->intercept_flags = flags;
    h->owner = p; h->id = index + 1; h->phase = phase; h->vaddr = vaddr; h->replacement = replacement; h->original = original;
    if (symbol) strcpy(h->symbol, symbol);
    *id = h->id; if (original) *original = NULL; atomic_store(&h->state, SH_WAITING);
    atomic_store(&p->hook_count, index + 1);
    event(p, h->id, "hook_registered", SH_WAITING, symbol ? symbol : "ELF vaddr with required Build ID");
    struct hook_query q = {h}; dl_iterate_phdr(bind_existing, &q);
    code = atomic_load(&h->state);
    if (code == SH_WAITING) event(p, h->id, "hook_waiting", code, h->selector.name);
    return code;
}
static int hook_symbol_selected(void *instance, const sh_module_selector_v1 *selector, const char *symbol,
                                uint32_t phase, void *replacement, void **original, sh_hook_id *id) {
    if (!symbol) return SH_INVALID;
    return register_hook(instance, selector, symbol, 0, phase, replacement, original, id, SH_EXCLUSIVE, NULL, NULL, 0);
}
static int hook_symbol(void *instance, const char *module, const char *symbol, uint32_t phase,
                       void *replacement, void **original, sh_hook_id *id) {
    if (!module) return SH_INVALID;
    sh_module_selector_v1 s = {sizeof(s), strchr(module, '/') ? NULL : module, strchr(module, '/') ? module : NULL, NULL};
    return hook_symbol_selected(instance, &s, symbol, phase, replacement, original, id);
}
static int hook_elf_vaddr(void *instance, const sh_module_selector_v1 *selector, uintptr_t vaddr, uint32_t phase,
                         void *replacement, void **original, sh_hook_id *id) {
    return register_hook(instance, selector, NULL, vaddr, phase, replacement, original, id, SH_EXCLUSIVE, NULL, NULL, 0);
}
static void notify_subscription(struct plugin *p, struct subscription *s, struct dl_phdr_info *info, uint32_t phase) {
    if (!s->callback || !(s->phases & phase) || !matches_path(&s->selector, info)) return;
    char build[129], detail[512]; module_build_id(info, build);
    if (s->selector.build_id[0] && strcmp(build, s->selector.build_id)) {
        event(p, 0, "module_version_mismatch", SH_VERSION_MISMATCH, info->dlpi_name); return;
    }
    sh_module_v1 module = {sizeof(module), phase, info->dlpi_name, build, info->dlpi_addr, module_id(info)};
    if (!module.load_id) { event(p, 0, "module_limit", SH_LIMIT, info->dlpi_name); return; }
    const char *label = phase == SH_MODULE_EXISTING ? "module_existing" : phase == SH_MODULE_BEFORE_CONSTRUCTORS ? "module_before_constructors" : phase == SH_MODULE_AFTER_CONSTRUCTORS ? "module_after_constructors" : "module_before_unload";
    snprintf(detail, sizeof(detail), "subscription=%u loadId=%" PRIu64 " bias=0x%" PRIxPTR " path=%.300s", s->id, module.load_id, module.load_bias, module.path);
    event(p, 0, label, 0, detail);
    module_callback_depth++; s->callback(s->user, &module); module_callback_depth--;
}
struct subscription_query { struct plugin *plugin; struct subscription *subscription; };
static int notify_existing(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size; struct subscription_query *q = data;
    notify_subscription(q->plugin, q->subscription, info, SH_MODULE_EXISTING); return 0;
}
static int subscribe_module(void *instance, const sh_module_selector_v1 *selector, uint32_t phases,
                            sh_module_callback_v1 callback, void *user, sh_subscription_id *id) {
    struct plugin *p = instance;
    if (!p->active || !p->registering || registration_owner != p || module_callback_depth || !callback || !id || !phases || (phases & ~15u)) return SH_INVALID;
    unsigned index = p->subscription_count; if (index == 16) return SH_LIMIT;
    struct subscription *s = &p->subscriptions[index]; int code = selector_copy(&s->selector, selector); if (code) return code;
    s->id = index + 1; *id = s->id; s->phases = phases; s->callback = callback; s->user = user;
    atomic_store(&p->subscription_count, index + 1);
    struct subscription_query q = {p, s}; dl_iterate_phdr(notify_existing, &q); return 0;
}
static void process_module(struct dl_phdr_info *info, uint32_t phase) {
    for (unsigned i = 0; i < instance_count; i++) {
        struct plugin *p = instances[i]; if (!atomic_load(&p->active)) continue;
        if (phase == SH_MODULE_BEFORE_CONSTRUCTORS) for (unsigned j = 0; j < p->hook_count; j++) bind_hook(&p->hooks[j], info, phase);
        for (unsigned j = 0; j < p->subscription_count; j++) notify_subscription(p, &p->subscriptions[j], info, phase);
        if (phase == SH_MODULE_BEFORE_UNLOAD) for (unsigned j = 0; j < p->hook_count; j++) {
            struct hook *h = &p->hooks[j];
            if (h->load_id && h->load_bias == info->dlpi_addr) {
                int code = detach_hook(h);
                event(p, h->id, "hook_unbound", code, info->dlpi_name);
                h->stub = NULL; h->address = 0; h->load_id = 0; if (h->original) *h->original = NULL;
                atomic_store(&h->hits, 0); atomic_store(&h->state, SH_WAITING);
            }
        }
    }
    if (phase == SH_MODULE_BEFORE_UNLOAD) for (unsigned i = 0; i < 128; i++) if (module_ids[i].phdr == info->dlpi_phdr) module_ids[i].phdr = NULL;
}
static void plugin_before_init(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; process_module(info, SH_MODULE_BEFORE_CONSTRUCTORS); }
static void plugin_after_init(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; process_module(info, SH_MODULE_AFTER_CONSTRUCTORS); }
static void plugin_before_fini(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; process_module(info, SH_MODULE_BEFORE_UNLOAD); }
static void initialize_hooks(void) {
    hooks_result = shadowhook_register_dl_init_callback(plugin_before_init, plugin_after_init, NULL);
    if (!hooks_result) hooks_result = shadowhook_register_dl_fini_callback(plugin_before_fini, NULL, NULL);
}
static int cleanup_failed_instance(struct dl_phdr_info *info, size_t size, void *data) {
    (void)info; (void)size; struct plugin *p = data; atomic_store(&p->active, 0);
    for (unsigned i = 0; i < p->hook_count; i++) {
        struct hook *h = &p->hooks[i]; int code = detach_hook(h);
        /* An in-flight replacement can still call its original after unhook.
         * Retain original storage and the mapped plugin until process exit. */
        if (!code) { h->stub = NULL; h->address = 0; }
        event(p, h->id, "failed_init_cleanup", code, h->symbol);
    }
    return 1;
}

static int hook_symbol_options(void *instance, const sh_module_selector_v1 *selector, const char *symbol,
        uint32_t phase, const sh_hook_options_v1 *options, void *replacement, void **original, sh_hook_id *id) {
    if (!options || options->struct_size < sizeof(*options) || options->mode > SH_CHAIN || !symbol) return SH_INVALID;
    return register_hook(instance, selector, symbol, 0, phase, replacement, original, id, options->mode, NULL, NULL, 0);
}
static int hook_elf_vaddr_options(void *instance, const sh_module_selector_v1 *selector, uintptr_t vaddr,
        uint32_t phase, const sh_hook_options_v1 *options, void *replacement, void **original, sh_hook_id *id) {
    if (!options || options->struct_size < sizeof(*options) || options->mode > SH_CHAIN) return SH_INVALID;
    return register_hook(instance, selector, NULL, vaddr, phase, replacement, original, id, options->mode, NULL, NULL, 0);
}
static int intercept_elf_vaddr(void *instance, const sh_module_selector_v1 *selector, uintptr_t vaddr,
        uint32_t phase, uint32_t flags, sh_interceptor_v1 callback, void *user, sh_hook_id *id) {
    if (!callback || flags & ~SH_SIMD_READ_WRITE) return SH_INVALID;
    return register_hook(instance, selector, NULL, vaddr, phase, NULL, NULL, id, 2, callback, user, flags);
}


static int hook_plt(void *instance, const sh_module_selector_v1 *caller, const char *symbol,
        uint32_t phase, void *replacement, void **original, sh_hook_id *id) {
    if (!symbol) return SH_INVALID;
    return register_hook(instance, caller, symbol, 0, phase, replacement, original, id, 3, NULL, NULL, 0);
}
struct registration_query { struct plugin *plugin; sh_registration_v1 callback; void *user; int result; };
static int run_registration(struct dl_phdr_info *info, size_t size, void *data) {
    (void)info; (void)size; struct registration_query *q = data;
    if (!q->plugin->active || q->plugin->registering) { q->result = SH_INVALID; return 1; }
    q->plugin->registering = 1; runtime_registration = 1; registration_owner = q->plugin;
    q->result = q->callback(&host, q->plugin, q->user);
    registration_owner = NULL; runtime_registration = 0; q->plugin->registering = 0;
    event(q->plugin, 0, "runtime_registration", q->result, "Native task batch");
    return 1;
}
static int register_tasks(void *instance, sh_registration_v1 callback, void *user) {
    if (!instance || !callback || module_callback_depth || runtime_registration) return SH_INVALID;
    struct registration_query q = {instance, callback, user, SH_INVALID};
    dl_iterate_phdr(run_registration, &q); return q.result;
}
struct remove_query { struct plugin *plugin; uint32_t id; int subscription, result; };
static int run_remove(struct dl_phdr_info *info, size_t size, void *data) {
    (void)info; (void)size; struct remove_query *q = data; struct plugin *p = q->plugin;
    if (q->subscription) {
        if (!q->id || q->id > p->subscription_count) q->result = SH_INVALID;
        else { p->subscriptions[q->id - 1].callback = NULL; q->result = SH_OK; }
    } else if (!q->id || q->id > p->hook_count) q->result = SH_INVALID;
    else {
        struct hook *h = &p->hooks[q->id - 1];
        q->result = h->jni_class_name[0] ? SH_UNSUPPORTED : detach_hook(h);
        if (!q->result) {
            h->cancelled = 1; h->stub = NULL; h->address = 0;
            atomic_store(&h->state, SH_CANCELLED);
            event(p, h->id, "hook_cancelled", SH_OK, h->symbol);
        }
    }
    return 1;
}
static int remove_hook(void *instance, sh_hook_id id) {
    if (!instance || module_callback_depth) return SH_INVALID;
    struct remove_query q = {instance, id, 0, SH_INVALID}; dl_iterate_phdr(run_remove, &q); return q.result;
}
static int unsubscribe_module(void *instance, sh_subscription_id id) {
    if (!instance || module_callback_depth) return SH_INVALID;
    struct remove_query q = {instance, id, 1, SH_INVALID}; dl_iterate_phdr(run_remove, &q); return q.result;
}
