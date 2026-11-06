#define _GNU_SOURCE
#include "next_api.h"
#include <assert.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
extern int m7_checks(int,char **);
static const struct eh_next_companion *companion;
static void (*hyos_callback)(const struct eh_hyos_args *);
static int hyos;
static void *serve(void *value) { companion->connected((int)(long)value); return NULL; }
static int connect_companion(void *handle) {
    (void)handle; int sockets[2]; assert(socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets)==0);
    pthread_t thread; assert(pthread_create(&thread,NULL,serve,(void *)(long)sockets[1])==0); pthread_detach(thread); return sockets[0];
}
static int register_hyos(const void *value) {
    const struct eh_hyos_module *module=value; assert(module->target_api_version==1); hyos_callback=module->specialized; return 0;
}
static const struct eh_next_runtime *runtime(void) {
    static const struct eh_next_runtime api={1,1,register_hyos}; return hyos ? &api : NULL;
}
int main(int argc,char **argv) {
    assert(argc==4); hyos=!strcmp(argv[2],"hyos");
    void *library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL); if (!library) fprintf(stderr,"%s\n",dlerror()); assert(library);
    const struct eh_next_module *module=dlsym(library,"zn_module"); companion=dlsym(library,"zn_companion_module"); assert(module && companion);
    assert(module->target_api_version==(hyos ? 4 : 3));
    const struct eh_next_api api={.connect_companion=connect_companion,.get_runtime=runtime};
    module->loaded(library,&api);
    if (hyos) { assert(hyos_callback); const struct eh_hyos_args args={"org.example.native","org.example.native","fixture"}; hyos_callback(&args); }
    char *arguments[]={argv[0],argv[3],NULL}; int result=m7_checks(2,arguments);
    if (!result) puts(hyos ? "M7_NEXT_ABI4_HYOS_FIXTURE_OK" : "M7_NEXT_ABI3_FIXTURE_OK");
    return result;
}
