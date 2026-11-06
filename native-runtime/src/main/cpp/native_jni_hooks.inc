#ifdef __ANDROID__
#include <jni.h>
static JavaVM *jni_vm;
static pthread_mutex_t jni_initialize_lock = PTHREAD_MUTEX_INITIALIZER;
static int jni_initialize_attempted, jni_initialize_result;
static jmethodID jni_class_name, jni_class_loader;
static jint (*jni_original_register)(JNIEnv *, jclass, const JNINativeMethod *, jint);
static jint (*jni_original_unregister)(JNIEnv *, jclass);
static void *jni_register_stub, *jni_unregister_stub;
static pthread_mutex_t jni_request_lock = PTHREAD_MUTEX_INITIALIZER;
static JNIEnv *current_jni_env(void) {
    JNIEnv *env = NULL;
    if (!jni_vm || (*jni_vm)->GetEnv(jni_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return NULL;
    return env; /* Does not attach threads or alter thread lifecycle. */
}
static int jni_matches(JNIEnv *env, struct hook *h, jclass cls, const char *name) {
    if (strcmp(name, h->jni_class_name)) return 0;
    if (h->jni_scope == SH_JNI_EXACT_CLASS) return (*env)->IsSameObject(env, h->jni_class, cls);
    jobject loader = (*env)->CallObjectMethod(env, cls, jni_class_loader);
    int match = (*env)->IsSameObject(env, h->jni_class, loader);
    if (loader) (*env)->DeleteLocalRef(env, loader);
    return match;
}
struct jni_module_query { struct hook *hook; uintptr_t address; };
static int jni_find_module(struct dl_phdr_info *info, size_t size, void *user) {
    (void)size; struct jni_module_query *q = user;
    if (q->address >= info->dlpi_addr && in_load(info, q->address - info->dlpi_addr, 4, PF_X)) {
        q->hook->load_bias = info->dlpi_addr; q->hook->load_id = module_id(info); return 1;
    }
    return 0;
}
/* Replace the method table entry, not its shared code address. Two ClassLoaders
 * may register the same C function: inline-hooking that address would cross scope. */
static jint jni_register_proxy(JNIEnv *env, jclass cls, const JNINativeMethod *methods, jint count) {
    if (count <= 0 || !methods || (*env)->ExceptionCheck(env)) return jni_original_register(env, cls, methods, count);
    jstring value = (*env)->CallObjectMethod(env, cls, jni_class_name);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return jni_original_register(env, cls, methods, count); }
    const char *name = (*env)->GetStringUTFChars(env, value, NULL);
    if (!name) { (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, value); return jni_original_register(env, cls, methods, count); }
    JNINativeMethod *table = malloc((size_t)count * sizeof(*table));
    if (!table) { (*env)->ReleaseStringUTFChars(env, value, name); (*env)->DeleteLocalRef(env, value); return jni_original_register(env, cls, methods, count); }
    memcpy(table, methods, (size_t)count * sizeof(*table));
    pthread_mutex_lock(&jni_request_lock);
    struct hook *selected[MAX_INSTANCES * MAX_HOOKS];
    int changed[MAX_INSTANCES * MAX_HOOKS]; unsigned selected_count = 0;
    for (jint k = 0; k < count; k++) {
        struct hook *chain[MAX_INSTANCES * MAX_HOOKS]; unsigned chain_count = 0;
        for (unsigned i = 0; i < instance_count; i++) {
            struct plugin *p = instances[i]; if (!atomic_load(&p->active) || p->registering) continue;
            for (unsigned j = 0; j < p->hook_count; j++) {
                struct hook *h = &p->hooks[j];
                if (!h->jni_class_name[0] || strcmp(h->jni_name, methods[k].name) || strcmp(h->jni_signature, methods[k].signature) || !jni_matches(env, h, cls, name)) continue;
                if (chain_count && (h->mode == SH_EXCLUSIVE || chain[0]->mode == SH_EXCLUSIVE)) {
                    atomic_store(&h->state, SH_CONFLICT); event(p, h->id, "jni_hook_conflict", SH_CONFLICT, chain[0]->owner->plugin_id); continue;
                }
                chain[chain_count++] = h;
            }
        }
        void *next = methods[k].fnPtr;
        for (unsigned j = chain_count; j > 0; j--) {
            struct hook *h = chain[j - 1];
            if (*h->original != next) *h->original = next;
            next = h->replacement;
            unsigned present = 0; for (unsigned i = 0; i < selected_count; i++) if (selected[i] == h) present = 1;
            if (!present) {
                changed[selected_count] = atomic_load(&h->state) != SH_OK || h->address != (uintptr_t)methods[k].fnPtr;
                selected[selected_count++] = h;
            }
            h->address = (uintptr_t)methods[k].fnPtr;
        }
        table[k].fnPtr = next;
    }
    jint result = jni_original_register(env, cls, table, count);
    /* No JNI calls here: preserve the VM's return value and pending exception. */
    for (unsigned i = 0; i < selected_count; i++) {
        struct hook *h = selected[i];
        if (result == JNI_OK) {
            h->stub = h; /* VM-owned method registration, not a ShadowHook trampoline. */
            if (changed[i]) {
                struct jni_module_query query = {h, h->address}; dl_iterate_phdr(jni_find_module, &query);
                atomic_store(&h->state, SH_OK); event(h->owner, h->id, "jni_hook_installed", SH_OK, name);
            }
        } else { atomic_store(&h->state, result); event(h->owner, h->id, "jni_registration_failed", result, name); }
    }
    free(table); (*env)->ReleaseStringUTFChars(env, value, name); (*env)->DeleteLocalRef(env, value);
    pthread_mutex_unlock(&jni_request_lock);
    return result;
}
static jint jni_unregister_proxy(JNIEnv *env, jclass cls) {
    pthread_mutex_lock(&jni_request_lock);
    jint result = jni_original_unregister(env, cls);
    if (result != JNI_OK || (*env)->ExceptionCheck(env)) { pthread_mutex_unlock(&jni_request_lock); return result; }
    jstring value = (*env)->CallObjectMethod(env, cls, jni_class_name);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); pthread_mutex_unlock(&jni_request_lock); return result; }
    const char *name = (*env)->GetStringUTFChars(env, value, NULL);
    if (!name) { (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, value); pthread_mutex_unlock(&jni_request_lock); return result; }
    for (unsigned i = 0; i < instance_count; i++) for (unsigned j = 0; j < instances[i]->hook_count; j++) {
        struct hook *h = &instances[i]->hooks[j];
        if (h->jni_class_name[0] && jni_matches(env, h, cls, name) && h->stub) {
            int code = detach_hook(h); event(h->owner, h->id, "jni_unregistered", code, name);
            if (!code) { h->stub = NULL; h->address = 0; h->load_id = 0; atomic_store(&h->state, SH_WAITING); }
        }
    }
    (*env)->ReleaseStringUTFChars(env, value, name); (*env)->DeleteLocalRef(env, value);
    pthread_mutex_unlock(&jni_request_lock);
    return result;
}
int sh_core_set_jni_env(void *environment) {
    JNIEnv *env = environment;
    pthread_mutex_lock(&jni_initialize_lock);
    if (jni_initialize_attempted) { int code = jni_initialize_result; pthread_mutex_unlock(&jni_initialize_lock); return code; }
    jni_initialize_attempted = 1;
    JavaVM *vm = NULL;
    jni_initialize_result = SH_INVALID;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) goto done;
    jclass cls = (*env)->FindClass(env, "java/lang/Class");
    if (!cls) goto done;
    jni_class_name = (*env)->GetMethodID(env, cls, "getName", "()Ljava/lang/String;");
    jni_class_loader = (*env)->GetMethodID(env, cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
    (*env)->DeleteLocalRef(env, cls);
    if (!jni_class_name || !jni_class_loader) goto done;
    jni_register_stub = shadowhook_hook_func_addr_2((void *)(*env)->RegisterNatives,
        (void *)jni_register_proxy, (void **)&jni_original_register, SHADOWHOOK_HOOK_WITH_UNIQUE_MODE);
    if (!jni_register_stub) { jni_initialize_result = shadowhook_get_errno(); goto done; }
    jni_unregister_stub = shadowhook_hook_func_addr_2((void *)(*env)->UnregisterNatives,
        (void *)jni_unregister_proxy, (void **)&jni_original_unregister, SHADOWHOOK_HOOK_WITH_UNIQUE_MODE);
    if (!jni_unregister_stub) {
        jni_initialize_result = shadowhook_get_errno();
        shadowhook_unhook(jni_register_stub); goto done;
    }
    jni_vm = vm; jni_initialize_result = 0;
done:
    pthread_mutex_unlock(&jni_initialize_lock); return jni_initialize_result;
}
static int hook_jni_method(void *instance, const sh_jni_method_v1 *method, const sh_hook_options_v1 *options,
        void *replacement, void **original, sh_hook_id *id) {
    struct plugin *p = instance; JNIEnv *env = current_jni_env();
    if (!p->active || !p->registering || registration_owner != p || runtime_registration || module_callback_depth || !env || !method || method->struct_size < sizeof(*method) || method->scope > SH_JNI_EXACT_LOADER ||
        !method->class_name || !method->method_name || !method->signature || !replacement || !original || !id ||
        !options || options->struct_size < sizeof(*options) || options->mode > SH_CHAIN ||
        strlen(method->class_name) >= 256 || strlen(method->method_name) >= 128 || strlen(method->signature) >= 256 ||
        (method->scope == SH_JNI_EXACT_CLASS && !method->exact_class)) return SH_INVALID;
    pthread_mutex_lock(&jni_request_lock);
    unsigned index = p->hook_count;
    if (index == MAX_HOOKS) { pthread_mutex_unlock(&jni_request_lock); return SH_LIMIT; }
    struct hook *h = &p->hooks[index]; h->owner = p; h->id = index + 1; h->mode = options->mode;
    h->replacement = replacement; h->original = original; h->jni_scope = method->scope;
    h->jni_class = method->exact_class ? (*env)->NewGlobalRef(env, method->exact_class) : NULL;
    if (method->exact_class && !h->jni_class) { pthread_mutex_unlock(&jni_request_lock); return SH_INVALID; }
    strcpy(h->jni_class_name, method->class_name); strcpy(h->jni_name, method->method_name); strcpy(h->jni_signature, method->signature);
    *id = h->id; *original = NULL; atomic_store(&h->state, SH_WAITING);
    atomic_store(&p->hook_count, index + 1); pthread_mutex_unlock(&jni_request_lock);
    event(p, h->id, "jni_waiting_registration", SH_WAITING, method->class_name);
    return SH_WAITING;
}
#else
static void *current_jni_env(void) { return NULL; }
static int hook_jni_method(void *instance, const sh_jni_method_v1 *method, const sh_hook_options_v1 *options,
        void *replacement, void **original, sh_hook_id *id) {
    (void)instance; (void)method; (void)options; (void)replacement; (void)original; (void)id; return SH_INVALID;
}
#endif
