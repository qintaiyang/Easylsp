#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern int m7_call(int);
extern int m7_value(int);
static int (*control)(int);
static int find_plugin(struct dl_phdr_info *info, size_t size, void *unused) {
    (void)size;(void)unused;
    void *handle = dlopen(info->dlpi_name,RTLD_NOW|RTLD_NOLOAD);
    if (handle) { void *symbol = dlsym(handle,"m7_control"); if (symbol) control=symbol; dlclose(handle); }
    return control ? 1 : 0;
}
int main(int argc, char **argv) {
    assert(argc==2);
    assert(!getenv("EASYLSP_NATIVE_CONFIG"));
    dl_iterate_phdr(find_plugin,NULL); assert(control);
    assert(m7_call(7)==108 && m7_value(7)==8);
    assert(control(0)==0 && m7_call(7)==8);
    assert(control(1)==0 && m7_call(7)==108);
    for (int i=0;i<2;i++) {
        void *future=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL); if (!future) fprintf(stderr,"%s\n",dlerror()); assert(future);
        int (*call)(int)=dlsym(future,"m7_call"); assert(call && call(7)==208);
        assert(dlclose(future)==0);
    }
    assert(control(3)==4);
    assert(control(2)==0);
    void *future=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL); assert(future); assert(dlclose(future)==0);
    assert(control(3)==4);
    assert(control(0)==0 && m7_call(7)==8 && m7_value(7)==8);
    puts("M7_NATIVE_OK caller=108 direct=8 runtime_remove=8 runtime_register=108 future=208 reload=2 unsubscribe=ok");
    return 0;
}
