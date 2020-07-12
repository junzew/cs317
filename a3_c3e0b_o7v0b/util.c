#include "util.h"
#include <string.h>
#include <ctype.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include "handler.h"
#include <sys/socket.h>
/**
* Utitily functions
*/

// Return true if string a starts with string b
int starts_with(char* a, char* b) {
	int len = strlen(b);
	return strncmp(a, b, len) == 0;
}

// Return true if username is matched
int check_username(char* username) {
	return !strcmp(username, "cs317");
}

// Capitalize first n letters
void capitalize(char* buf, int n) {
	char* curr = buf;
	// while (*curr++ = toupper(*curr));
	while(*curr && n) {
		*curr = toupper(*curr);
		curr++;
		n--;
	}
}

// Return 1 if  of arg count = expected_argc, 0 otherwise
int check_argc(char* buf, int expected_argc) {
	char copy[strlen(buf)+1];
	strcpy(copy, buf);
	char* arg = strtok(copy, " ");
	int argc = 0;
	while(arg != NULL) {
		arg = strtok(NULL, " ");
		argc++;
	}
	return argc == expected_argc;
}

// Retrieve the second argument of a command
// Example: 
// Input : USER anonymous
// Return: anonymous
char* get_second_arg(char* buf) {
	char copy[strlen(buf) + 1];
    strcpy(copy, buf);
    char* command = strtok(copy, " ");
    char* second_arg = strtok(NULL, " ");
    char* result = malloc(strlen(second_arg) - 1);
    strncpy(result, second_arg, strlen(second_arg) - 2); // remove /r/n
    result[strlen(second_arg) - 2] = '\0';
    return result;
}

// Concatenate two strings
char* concat(const char* s1, const char* s2) {
	char* concat = malloc(strlen(s1) + strlen(s2)+1);
	strcpy(concat, s1);
	strcat(concat, s2);
	return concat;
}

// send message through sockfd
void send_msg(int sockfd, char* message) {
  if (send(sockfd, message, strlen(message), 0) == -1) {
    perror("send");
  }
}


