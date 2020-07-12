#include "handler.h"
#include "util.h"
#include "dir.h"
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <time.h>
#include <stdlib.h>
#include <netdb.h>
#include <ifaddrs.h>
#include <linux/if_link.h>
#include <pthread.h>
#include <stdint.h>
#include <errno.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/sendfile.h>
#include <sys/time.h>
/**
* Handlers for processing commands
*/

// initial working directory
char init_dir[100];
// file transmision type: 0 for ASCII, 1 for Image
int binary_flag;

// 1 if in passive mode, 0 otherwise
int passive_mode;

// Socket for accpeting data conncection
intptr_t data_channel;
// Actual socket for sending data
int data_sock;

// The thread for the data channel
pthread_t data_thread;
// mutex for data_sock
pthread_mutex_t mutex_sock;

// Error code
int errno;

// state of the data socket
int is_data_sock_connected = 0;
// state of the thread for data transfer
int is_thread_running = 0;

// set up
void init() {
  getcwd(init_dir, sizeof(init_dir));
  printf("Initial working directory %s\n", init_dir);
  binary_flag = 0; // ASCII mode by default
  passive_mode = 0;
  pthread_mutex_init(&mutex_sock, NULL);// init mutex
}

void clean_up() {
  passive_mode = 0;
  pthread_mutex_destroy(&mutex_sock);
  printf("Cleaning up...\n");
}

// Process command, dispatch to handlers
// Parameters:
// buf     string buffer containing the command and arguments
// buflen  length of the buffer
// message the response message to be sent
// msglen  the length of the message
// sock    the socket to communicate to the client
//         RETR and NLST need sock to send a mark
// Return -1 if command is QUIT, 0 otherwise
int process_command(char* buf, int buflen, char* message, int msglen, int sock) {
  int res = 0;
  if (starts_with(buf, "USER ")) {
    handle_user(buf, buflen, message, msglen);
  } else if (starts_with(buf, "CWD ")) {
    handle_cwd(buf, buflen, message, msglen);
  } else if (!strcmp(buf, "CDUP\r\n")) {
    handle_cdup(buf, buflen, message, msglen);
  } else if (!strcmp(buf, "PWD\r\n")) {
    handle_pwd(buf, buflen, message, msglen);
  } else if (starts_with(buf, "TYPE")) {
    handle_type(buf, buflen, message, msglen);
  } else if (starts_with(buf, "MODE")) {
    handle_mode(buf, buflen, message, msglen);
  } else if (starts_with(buf, "STRU")) {
    handle_stru(buf, buflen, message, msglen);
  } else if (starts_with(buf, "RETR")) {
    handle_retr(buf, buflen, message, msglen, sock);
  } else if (!strcmp(buf, "PASV\r\n")){
    handle_pasv(buf, buflen, message, msglen);
  } else if (!strcmp(buf, "NLST\r\n")) {
    handle_nlst(buf, buflen, message, msglen, sock);
  } else if (!strcmp(buf, "QUIT\r\n")) {
    res = -1;
  } else {
    snprintf(message, msglen, "500 unknown command.\n");
  }
  return res;
}

// Format the IP address and port number into:(h1,h2,h3,h4,p1,p2)
// TCP port = p1*256+p2, IP address = h1.h2.h3.h4
void format_ip_and_port(char* ip, int port, char* res, int len) {
  int n1 = port / 256;
  int n2 = port % 256;
  int i;
  for (i = 0; i < strlen(ip); i++) {
    if(ip[i]=='.') {
      ip[i] = ',';
    }
  }
  snprintf(res, len, "(%s,%d,%d)", ip, n1, n2);
}

// Obtain an IP address and store it in res
 void get_IP_address(char* res, int len) {
   // source: man page of getifaddrs
	struct ifaddrs *ifaddr, *ifa;
	int family, s, n;
   char host[NI_MAXHOST];

   if (getifaddrs(&ifaddr) == -1) {
       perror("getifaddrs");
       exit(EXIT_FAILURE);
   }

   /* Walk through linked list, maintaining head pointer so we
      can free list later */

   for (ifa = ifaddr, n = 0; ifa != NULL; ifa = ifa->ifa_next, n++) {
       if (ifa->ifa_addr == NULL)
           continue;

       family = ifa->ifa_addr->sa_family;

       /* Display interface name and family (including symbolic
          form of the latter for the common families) */

       printf("%-8s %s (%d)\n",
              ifa->ifa_name,
              (family == AF_PACKET) ? "AF_PACKET" :
              (family == AF_INET) ? "AF_INET" :
              (family == AF_INET6) ? "AF_INET6" : "???",
              family);

       /* For an AF_INET* interface address, display the address */

       if (family == AF_INET || family == AF_INET6) {
           s = getnameinfo(ifa->ifa_addr,
                   (family == AF_INET) ? sizeof(struct sockaddr_in) :
                                         sizeof(struct sockaddr_in6),
                   host, NI_MAXHOST,
                   NULL, 0, NI_NUMERICHOST);
           if (s != 0) {
               printf("getnameinfo() failed: %s\n", gai_strerror(s));
               exit(EXIT_FAILURE);
           }

           printf("\t\taddress: <%s>\n", host);

       } else if (family == AF_PACKET && ifa->ifa_data != NULL) {
           struct rtnl_link_stats *stats = ifa->ifa_data;

           printf("\t\ttx_packets = %10u; rx_packets = %10u\n"
                  "\t\ttx_bytes   = %10u; rx_bytes   = %10u\n",
                  stats->tx_packets, stats->rx_packets,
                  stats->tx_bytes, stats->rx_bytes);
       }
    }

   snprintf(res, len, host);
   freeifaddrs(ifaddr);
}

void handle_user(char* buf, int buflen, char* message, int msglen) {
	if(check_argc(buf, 2)) {
		snprintf(message, msglen, "331 cannot change from guest user.\n");
	} else {
		snprintf(message, msglen, "500 unknown command.\n");
	}
}

void handle_cwd (char* buf, int buflen, char* message, int msglen) {
	if(check_argc(buf, 2)) {
		char* directory = get_second_arg(buf);
		char curr[100];
		getcwd(curr, sizeof(curr));
		printf("changing directory to: %s\n", directory);
		if (starts_with(directory, "./") || starts_with(directory, "..")) {
			// security violation
			snprintf(message, msglen, "550 No access to directory.\n");
		} else if(chdir(directory) == 0) {
			getcwd(curr, sizeof(curr));
			printf("Working directory: %s\n", curr);
			snprintf(message, msglen, "250 Directory successfully changed.\n");
		} else {
			getcwd(curr, sizeof(curr));
			printf("Working directory: %s\n", curr);
			snprintf(message, msglen, "550 Failed to change directory.\n");
		}
    free(directory);
	} else {
		snprintf(message, msglen, "500 unknown command.\n");
	}
}

void handle_cdup(char* buf, int buflen, char* message, int msglen) {
	char curr[100];
	getcwd(curr, sizeof(curr));
	if (!strcmp(curr, init_dir)) {
		// security violation
		snprintf(message, msglen, "550 No access to directory.\n");
	} else {
		if(chdir("../") == 0) {
			snprintf(message, msglen, "250 Directory successfully changed.\n");
		} else {
			snprintf(message, msglen, "550 Failed to change directory.\n");
		}
	}
}

void handle_pwd (char* buf, int buflen, char* message, int msglen) {
	char curr[100];
	getcwd(curr, sizeof(curr));
	printf("Working directory: %s\n", curr);
	snprintf(message, sizeof(curr), "257 \"%s\"\n", curr);
}

void handle_type(char* buf, int buflen, char* message, int msglen) {
	if (check_argc(buf, 2)) {
		char* mode = get_second_arg(buf);
		if(!strcmp(mode, "A") || !strcmp(mode, "a")) {
			binary_flag = 0;
			snprintf(message, msglen, "200 Switching to ASCII mode.\n");
		} else if (!strcmp(mode, "I") || !strcmp(mode, "i")) {
			binary_flag = 1;
			snprintf(message, msglen, "200 Switching to Binary mode.\n");
		} else {
			snprintf(message, msglen, "500 Unrecognised TYPE command.\n");
		}
    free(mode);
	} else {
		snprintf(message, msglen, "500 Unrecognised TYPE command.\n");
	}
}

void handle_mode(char* buf, int buflen, char* message, int msglen) {
  if (check_argc(buf, 2)) {
		char* mode = get_second_arg(buf);
    // only accept MODE S
		if(!strcmp(mode, "S") || !strcmp(mode, "s")) {
			snprintf(message, msglen, "200 Mode set to S.\n");
		} else {
			snprintf(message, msglen, "504 Bad MODE command.\n");
		}
    free(mode);
	} else {
		snprintf(message, msglen, "504 Bad MODE command.\n");
	}
}

void handle_stru(char* buf, int buflen, char* message, int msglen) {
  if (check_argc(buf, 2)) {
		char* stru = get_second_arg(buf);
    // only accept STRU F
		if(!strcmp(stru, "F") || !strcmp(stru, "f")) {
			snprintf(message, msglen, "200 Structure set to F.\n");
		} else {
			snprintf(message, msglen, "504 Bad STRU command.\n");
		}
    free(stru);
	} else {
		snprintf(message, msglen, "504 Bad STRU command.\n");
	}
}

void handle_retr(char* buf, int buflen, char* message, int msglen, int sock) {
  if (passive_mode) {
    if (check_argc(buf, 2)) {
      char* file_name = get_second_arg(buf);

      pthread_mutex_lock(&mutex_sock);
      int fd = open(file_name, O_RDONLY);
      if (fd == -1) {
        snprintf(message, msglen, "550 Failed to open file.\n");
        perror("open file");
      } else {
        struct stat st;
        stat(file_name, &st);
        off_t size = st.st_size; // get file size
        printf("sending file %s...\n", file_name);

        // send mark
        snprintf(message, msglen, "150 Opening data connection for %s (%d bytes).\n",file_name, size);
        send_msg(sock, message);

        // source: http://stackoverflow.com/a/11965442
        char buffer[1024];
        int sent = 0;
        off_t offset = 0;
        off_t remain_data = size;
        while (((sent = sendfile(data_sock, fd, &offset, 1024)) > 0) && (remain_data > 0)) {
          remain_data -= sent;
          fprintf(stdout, "Server sent %d bytes from file's data, offset is now : %d and remaining data = %d\n", sent, offset, remain_data);
        }
        snprintf(message, msglen, "226 File transfer success.\n");

        if(close(fd)) {
          perror("close file");
        }
      }
      passive_mode = 0;// set to active mode
      if (is_data_sock_connected) {
        printf("closing data_sock\n");
        close(data_sock);
        is_data_sock_connected = 0;
      }
      if (is_thread_running) {
        pthread_cancel(data_thread);
        printf("canceling thread in handle_retr\n");
        is_thread_running = 0;
      }
      pthread_mutex_unlock(&mutex_sock);

      free(file_name);
    } else {
      snprintf(message, msglen, "550 Failed to open file.\n");
    }
  } else {
      snprintf(message, msglen, "425 Use PASV first.\n");
  }
}

// the routine to be executed in a separate thread to accept data connection
void* accept_data(void* ptr_data_channel) {
  intptr_t data_channel = (intptr_t) ptr_data_channel;
  struct sockaddr res;
  socklen_t sin_size = sizeof(res);
  printf("data_channel accept in accept_data\n");

  pthread_setcanceltype(PTHREAD_CANCEL_ASYNCHRONOUS,NULL);
  // polling
  while((data_sock = accept(data_channel, &res, &sin_size)) == -1) {}
  printf("data_sock opened in accept_data...\n");

  pthread_mutex_lock(&mutex_sock);
  is_data_sock_connected = 1;
  printf("closing data channel in accept_data\n");
  close(data_channel);
  is_thread_running = 0;
  pthread_mutex_unlock(&mutex_sock);
 }

void handle_pasv(char* buf, int buflen, char* message, int msglen) {
	passive_mode = 1; // Enter passive mode
	printf("Handling PASV...\n");

  pthread_mutex_lock(&mutex_sock);
  if (is_data_sock_connected) {
    close(data_sock);
    printf("closing data_sock in handle_pasv\n");
    is_data_sock_connected = 0;
  }
  if (is_thread_running) {
    close(data_channel);
    printf("closing data_channel in handle_pasv\n");
    pthread_cancel(data_thread);
    is_thread_running = 0;
  }
  // Create socket for data connection
  if((data_channel = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
    perror("data channel socket");
  }
  // Set socket to be non-blocking
  fcntl(data_channel, F_SETFL, O_NONBLOCK);
  pthread_mutex_unlock(&mutex_sock);

	struct sockaddr_in addr;
	addr.sin_family= AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_ANY);
  // Generate random port
  srand(time(NULL));
  int port = rand() % (65536 - 1024) + 1024;
	printf("PORT: %d\n", port);
	addr.sin_port = htons(port);

  while(bind(data_channel, (struct sockaddr*)& addr, sizeof(addr)) == -1
      && errno == EADDRINUSE) { // If port in use, try binding to another port
      perror("bind");
      port = rand() % (65536 - 1024) + 1024;
      printf("Try another port#=%d\n", port);
      addr.sin_port = htons(port);
  }

	if((listen(data_channel, 10) == -1)) {
      perror("data channel listen");
  }

  // Format IP address and port number
  char* my_ip = malloc(sizeof(char)*16);
  get_IP_address(my_ip, 16);
  printf("My IP address is: %s\n", my_ip);
  char* nums = malloc(sizeof(char)*64);
  format_ip_and_port(my_ip, port, nums, 64);
  printf("Formated IP address and port number: %s\n", nums);
  snprintf(message, msglen, "227 Entering Passive Mode %s.\n", nums);
  free(my_ip);
  free(nums);

  // Create new thread to accept incoming connections
  pthread_mutex_lock(&mutex_sock);
  int rc = pthread_create(&data_thread, NULL, accept_data, (void*)data_channel);
  if (rc) {
    printf("ERROR; return code from pthread_create() is %d\n", rc);
  } else {
    is_thread_running = 1;
    printf("thread is running\n");
  }
  pthread_mutex_unlock(&mutex_sock);

}

void handle_nlst(char* buf, int buflen, char* message, int msglen, int sock) {
	if(passive_mode) {
    int num_printed;
    // set timeout
    const double timeout = 60000; // 60000ms = 60s = 1min
    // http://stackoverflow.com/questions/2150291/how-do-i-measure-a-time-interval-in-c
    struct timeval t1, t2;
    double elapsed = 0;
    gettimeofday(&t1, NULL);
    int is_timeout = 0;
    while (1) {
      // wait until a connection is established
      gettimeofday(&t2, NULL);
      elapsed = (t2.tv_sec - t1.tv_sec) * 1000.0;      // sec to ms
      elapsed += (t2.tv_usec - t1.tv_usec) / 1000.0;   // us to ms
      if (elapsed > timeout) {
        // timeout
        is_timeout = 1;
        break;
      }
      pthread_mutex_lock(&mutex_sock);
      if (is_data_sock_connected) {
        pthread_mutex_unlock(&mutex_sock);
        break;
      }
      pthread_mutex_unlock(&mutex_sock);
    }

    pthread_mutex_lock(&mutex_sock);
    if (!is_timeout) {
      // send mark
      send_msg(sock, "150 Here comes the directory listing.\n");
      if ((num_printed = listFiles(data_sock, ".")) == -1) {
        snprintf(message, msglen, "451 Error reading directory from disk.\n");
      } else {
        printf("Printed %d directory entries\n", num_printed);
        snprintf(message, msglen, "226 Directory send OK.\n");
      }
    } else {
      snprintf(message, msglen, "425 Failed to establish connection.\n");
    }
		passive_mode = 0;// set to active mode
    if (is_data_sock_connected) {
		  printf("closing data_sock\n");
		  close(data_sock);
      is_data_sock_connected = 0;
    }
    if (is_thread_running) {
      printf("closing data_channel\n");
      close(data_channel);
      pthread_cancel(data_thread);
      printf("canceling thread in handle_nlst\n");
      is_thread_running = 0;
    }
    pthread_mutex_unlock(&mutex_sock);

  } else {
    snprintf(message, msglen, "425 Use PASV first.\n");
  }
}
