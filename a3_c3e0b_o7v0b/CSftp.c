#include <sys/types.h>
#include <sys/socket.h>
#include <stdio.h>
#include "dir.h"
#include "usage.h"
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <netinet/in.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <sys/wait.h>
#include <signal.h>
#include "util.h"
#include "handler.h"
#define BACKLOG 10   // how many pending connections queue will hold
#define BUFFER_SIZE 1024
char buf[BUFFER_SIZE];

void sigchld_handler(int s)
{
    // waitpid() might overwrite errno, so we save and restore it:
    int saved_errno = errno;

    while(waitpid(-1, NULL, WNOHANG) > 0);

    errno = saved_errno;
}


// get sockaddr, IPv4 or IPv6:
void *get_in_addr(struct sockaddr *sa)
{
    if (sa->sa_family == AF_INET) {
        return &(((struct sockaddr_in*)sa)->sin_addr);
    }

    return &(((struct sockaddr_in6*)sa)->sin6_addr);
}

// Here is an example of how to use the above function. It also shows
// one how to get the arguments passed on the command line.

int main(int argc, char **argv) {

    // This is some sample code feel free to delete it
    // This is the main program for the thread version of nc

    int i;
    
    // Check the command line arguments
    if (argc != 2) {
      usage(argv[0]);
      return -1;
    }

    // This is how to call the function in dir.c to get a listing of a directory.
    // It requires a file descriptor, so in your code you would pass in the file descriptor 
    // returned for the ftp server's data connection
    
    // printf("Printed %d directory entries\n", listFiles(1, "."));

    // tutorial code: (Beej's guide)
    int sockfd, new_fd;  // listen on sock_fd, new connection on new_fd
    struct addrinfo hints, *servinfo, *p;
    struct sockaddr_storage their_addr; // connector's address information
    socklen_t sin_size;
    struct sigaction sa;
    int yes=1;
    char s[INET6_ADDRSTRLEN];
    int rv;

    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE; // use my IP

    const char* PORT = argv[1];
    if ((rv = getaddrinfo(NULL, PORT, &hints, &servinfo)) != 0) {
        fprintf(stderr, "getaddrinfo: %s\n", gai_strerror(rv));
        return 1;
    }

    // loop through all the results and bind to the first we can
    for(p = servinfo; p != NULL; p = p->ai_next) {
        if ((sockfd = socket(p->ai_family, p->ai_socktype,
                p->ai_protocol)) == -1) {
            perror("server: socket");
            continue;
        }
        if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &yes,
                sizeof(int)) == -1) {
            perror("setsockopt");
            exit(1);
        }
        if (bind(sockfd, p->ai_addr, p->ai_addrlen) == -1) {
            close(sockfd);
            perror("server: bind");
            continue;
        }
        break;
    }
    
    freeaddrinfo(servinfo); // all done with this structure

    if (p == NULL)  {
        fprintf(stderr, "server: failed to bind\n");
        exit(1);
    }

    if (listen(sockfd, BACKLOG) == -1) {
        perror("listen");
        exit(1);
    }
    printf("listening on port: %s\n", PORT);

    sa.sa_handler = sigchld_handler; // reap all dead processes
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    if (sigaction(SIGCHLD, &sa, NULL) == -1) {
        perror("sigaction");
        exit(1);
    }

    printf("server: waiting for connections...\n");

    while(1) {  // main accept() loop
        sin_size = sizeof their_addr;
        new_fd = accept(sockfd, (struct sockaddr *)&their_addr, &sin_size);
        if (new_fd == -1) {
            perror("accept");
            continue;
        }

        inet_ntop(their_addr.ss_family,
            get_in_addr((struct sockaddr *)&their_addr),
            s, sizeof s);

        printf("server: got connection from %s\n", s);
        send_msg(new_fd, "220 connected.\n");
        if (!fork()) { // this is the child process
            close(sockfd); // child doesn't need the listener
            init();
            while(1) {
                memset(buf, 0, sizeof buf);
                int numbytes;
                if((numbytes = recv(new_fd, buf, BUFFER_SIZE-1, 0)) == -1) {
                    perror("recv server err");
                }
                printf("received: %s", buf);
                capitalize(buf, 4);
                // Handle user log in
                if (starts_with(buf, "USER ")) {
                    char* username = get_second_arg(buf);
                    if(check_username(username)) {
                        send_msg(new_fd, "230 login successful.\n");
                        printf("user logged in\n");
                        free(username);
                        break;
                    } else {
                        send_msg(new_fd, "331 Wrong username.\n");
                        free(username);
                        continue;
                    }
                } else if (!strcmp(buf, "QUIT\r\n")) {
                    break;
                }
                send_msg(new_fd, "530 Please login with USER.\n");
            }
            char* message = malloc(sizeof(char) * 1024);
            while(strcmp(buf, "QUIT\r\n")) {
                int numbytes;
                if((numbytes = recv(new_fd, buf, 1024-1, 0)) == -1) {
                    perror("recv server err");
                }
                buf[numbytes] = '\0';
                printf("received %s", buf);
                capitalize(buf, 4);
                // Handle commands
                if(process_command(buf, BUFFER_SIZE, message, 1024, new_fd) == -1) {
                  break;
                } else {
                  send_msg(new_fd, message);
                }
            }
            free(message);
            clean_up();
            send_msg(new_fd, "221 Goodbye.\n");
            printf("closing connection\n");
            close(new_fd);
            exit(0);
        }
        
        close(new_fd);  // parent doesn't need this
    }

    return 0;

}
