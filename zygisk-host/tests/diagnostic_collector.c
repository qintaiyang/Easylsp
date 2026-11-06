#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <stddef.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
int main(int argc,char **argv) {
 if(argc!=3)return 2;
 int fd=socket(AF_UNIX,SOCK_SEQPACKET,0);
 struct sockaddr_un a={.sun_family=AF_UNIX};strcpy(a.sun_path+1,argv[1]);
 if(bind(fd,(void*)&a,offsetof(struct sockaddr_un,sun_path)+1+strlen(argv[1]))||listen(fd,1))return 3;
 puts("READY");fflush(stdout);
 int client=accept(fd,0,0);if(client<0)return 4;
 FILE *out=fopen(argv[2],"wb");if(!out)return 5;
 struct timeval timeout={.tv_sec=3};setsockopt(client,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));
 char buffer[4096];ssize_t n;unsigned count=0;
 while((n=recv(client,buffer,sizeof(buffer),0))>0){fwrite(buffer,1,n,out);count++;}
 fclose(out);printf("packets=%u\n",count);return count?0:6;
}
