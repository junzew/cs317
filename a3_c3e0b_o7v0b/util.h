#ifndef _UTILH__

#define _UTILH__
int starts_with(char*, char*);
int check_username(char*);
int check_argc(char*, int);
void capitalize(char*, int); 
char* get_second_arg(char*);
char* concat(const char*, const char*);
void send_msg(int, char*);
#endif
