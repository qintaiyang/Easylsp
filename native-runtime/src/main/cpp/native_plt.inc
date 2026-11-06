/* AArch64 caller-local PLT relocation. Serialized by the linker lock. No
 * provider API or linker-private structure survives the specialization callback. */
static uintptr_t plt_pointer(const struct dl_phdr_info *info, uintptr_t value) {
    return in_load(info, value, 1, PF_R) ? info->dlpi_addr + value : value;
}
static int plt_replace(struct hook *h, void *expected, void *replacement) {
    long page_size = sysconf(_SC_PAGESIZE);
    void *page = (void *)(h->address & ~((uintptr_t)page_size - 1));
    if (mprotect(page, (size_t)page_size, h->plt_protection | PROT_WRITE)) return SH_LOAD_FAILED;
    int changed = __atomic_compare_exchange_n((void **)h->address, &expected, replacement, 0, __ATOMIC_RELEASE, __ATOMIC_ACQUIRE);
    if (mprotect(page, (size_t)page_size, h->plt_protection)) {
        if (changed) __atomic_store_n((void **)h->address, expected, __ATOMIC_RELEASE);
        return SH_LOAD_FAILED;
    }
    return changed ? SH_OK : SH_CONFLICT;
}
static int bind_plt(struct hook *h, const struct dl_phdr_info *info) {
    const ElfW(Dyn) *dynamic = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
        dynamic = (const ElfW(Dyn) *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    if (!dynamic) return SH_INVALID_ADDRESS;
    const ElfW(Rela) *relocations = NULL; const ElfW(Sym) *symbols = NULL; const char *strings = NULL;
    size_t bytes = 0, string_size = 0; int rela = 0;
    for (const ElfW(Dyn) *d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL: relocations = (const ElfW(Rela) *)plt_pointer(info, d->d_un.d_ptr); break;
            case DT_PLTRELSZ: bytes = d->d_un.d_val; break;
            case DT_PLTREL: rela = d->d_un.d_val == DT_RELA; break;
            case DT_SYMTAB: symbols = (const ElfW(Sym) *)plt_pointer(info, d->d_un.d_ptr); break;
            case DT_STRTAB: strings = (const char *)plt_pointer(info, d->d_un.d_ptr); break;
            case DT_STRSZ: string_size = d->d_un.d_val; break;
        }
    }
    if (!rela || !relocations || !symbols || !strings) return SH_UNSUPPORTED;
    uintptr_t address = 0;
    for (size_t i = 0; i < bytes / sizeof(*relocations); i++) {
        const ElfW(Rela) *r = &relocations[i];
#ifdef __aarch64__
        if (ELF64_R_TYPE(r->r_info) != R_AARCH64_JUMP_SLOT) continue;
#else
        if (ELF64_R_TYPE(r->r_info) != R_X86_64_JUMP_SLOT) continue;
#endif
        const ElfW(Sym) *symbol = &symbols[ELF64_R_SYM(r->r_info)];
        if (symbol->st_name >= string_size || strcmp(strings + symbol->st_name, h->symbol)) continue;
        if (address) return SH_AMBIGUOUS;
        if (!in_load(info, r->r_offset, sizeof(void *), PF_R) || (r->r_offset % sizeof(void *))) return SH_INVALID_ADDRESS;
        address = info->dlpi_addr + r->r_offset;
    }
    if (!address) return SHADOWHOOK_ERRNO_HOOK_DLSYM;
    if (address_owner(address, NULL)) return SH_CONFLICT;
    h->address = address;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        uintptr_t offset = address - info->dlpi_addr;
        if (p->p_type == PT_LOAD && offset >= p->p_vaddr && offset - p->p_vaddr < p->p_memsz)
            h->plt_protection = ((p->p_flags & PF_R) ? PROT_READ : 0) | ((p->p_flags & PF_W) ? PROT_WRITE : 0) | ((p->p_flags & PF_X) ? PROT_EXEC : 0);
    }
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *p = &info->dlpi_phdr[i];
        uintptr_t offset = address - info->dlpi_addr;
        if (p->p_type == PT_GNU_RELRO && offset >= p->p_vaddr && offset - p->p_vaddr < p->p_memsz)
            h->plt_protection &= ~PROT_WRITE;
    }
    *h->original = __atomic_load_n((void **)address, __ATOMIC_ACQUIRE);
    int result = plt_replace(h, *h->original, h->replacement);
    if (!result) h->stub = h;
    return result;
}
static int detach_plt(struct hook *h) {
    return h->stub ? plt_replace(h, h->replacement, *h->original) : SH_OK;
}
