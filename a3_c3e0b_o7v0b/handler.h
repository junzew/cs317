#ifndef _HANDLERH_

#define _HANDLERH_
void init();
void clean_up();
int process_command(char*, int, char*, int, int);
void handle_user(char*, int, char*, int);
void handle_cwd (char*, int, char*, int);
void handle_cdup(char*, int, char*, int);
void handle_pwd (char*, int, char*, int);
void handle_type(char*, int, char*, int);
void handle_mode(char*, int, char*, int);
void handle_stru(char*, int, char*, int);
void handle_retr(char*, int, char*, int, int);
void handle_pasv(char*, int, char*, int);
void handle_nlst(char*, int, char*, int, int);
#endif
