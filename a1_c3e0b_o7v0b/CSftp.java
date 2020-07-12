
import java.io.*;
import java.net.Socket;

//
// This is an implementation of a simplified version of a command 
// line ftp client. The program always takes two arguments
//


public class CSftp {
    static final int MAX_LEN = 255;
    static final int ARG_CNT = 2;

    // The socket used for control connection
    private static Socket socket = null;

    public static void main(String[] args) {
        byte cmdString[] = new byte[MAX_LEN];

        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
        // then exit.

        if (args.length < 1 || args.length > ARG_CNT) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            return;
        }
        String host = args[0];
        int port = 21;
        if (args.length == ARG_CNT) {
            port = Integer.valueOf(args[1]);
        }
        // Connect to the FTP server
        try {
            socket = new Socket(host, port);
            receive(socket);
        } catch (IOException e) {
//            e.printStackTrace();
            System.out.println("0xFFFC Control connection to " + host + " on port " + port + " failed to open.");
            System.exit(-1);
        }
        try {
            for (int len = 1; len > 0; ) {
                System.out.print("csftp> ");
                len = System.in.read(cmdString); // len is The number of bytes actually read
                if (len <= 0)
                    break;
                // Start processing the command here.
                String command = new String(cmdString, 0, len, "UTF-8");
                if (command.startsWith("#") || command.startsWith("\n")) {
                    /** Skip lines starting with # and empty lines **/
                } else if (command.startsWith("user")) {
                    handleUser(command);
                } else if (command.startsWith("pw")) {
                    handlePassword(command);
                } else if (command.startsWith("quit")) {
                    handleQuit();
                } else if (command.startsWith("get")) {
                    handleGet(command);
                } else if (command.startsWith("features")) {
                    handleFeatures(command);
                } else if (command.startsWith("cd")) {
                    handleChangeDirectory(command);
                } else if (command.startsWith("dir")) {
                    handleDir(command);
                } else {
                    System.out.println("0x001 Invalid command.");
                }
            }
            socket.close();
        } catch (IOException exception) {
            System.err.println("0xFFFE Input error while reading commands, terminating.");
            System.exit(0);
        }
    }

    // Handler for the 'dir' command
    private static void handleDir(String command) {
        if (command.trim().equals("dir")) {
            send("PASV");
            String line = receive(socket);
            if (line != null && line.startsWith("227")) {
                getListOfFiles(line);
            }
        } else {
            System.out.println("0x001 Invalid command.");
        }
    }

    // Format of line: "227 Entering Passive Mode (a,b,c,d,e,f)"
    // host: a.b.c.d
    // port: e * 256 + f
    private static void getListOfFiles(String line) {
        Socket dataSocket;
        String host = extractHost(line);
        int port = extractPort(line);
        try {
            dataSocket = new Socket(host, port);
        } catch (IOException e) {
//            e.printStackTrace();
            System.out.println("0x3A2 Data transfer connection to " + host + " on port " + port + " failed to open");
            return;
        }
        send("LIST");
        String res = receive(socket); // 150 Here comes the directory listing
        if (res!= null && (res.startsWith("150") || res.startsWith("125"))) {
            receive(dataSocket, false);
            receive(socket); // 226 Directory send OK | 226 Transfer done (but failed to open directory).
        }
        try {
            dataSocket.close();
        } catch (IOException e) {
//            e.printStackTrace();
        }
    }
    // parse and return the host
    private static String extractHost(String line) {
        String a, b, c, d;
        int openBracketIndex = line.indexOf("(");
        int closingBracketIndex = line.indexOf(")");
        String[] numbers = line.substring(openBracketIndex + 1, closingBracketIndex).split(",");
        a = numbers[0];
        b = numbers[1];
        c = numbers[2];
        d = numbers[3];
        String host = a + "." + b + "." + c + "." + d;
        return host;
    }
    // parse and return the port number
    private static int extractPort(String line) {
        int e, f;
        int openBracketIndex = line.indexOf("(");
        int closingBracketIndex = line.indexOf(")");
        String[] numbers = line.substring(openBracketIndex + 1, closingBracketIndex).split(",");
        e = Integer.valueOf(numbers[4]);
        f = Integer.valueOf(numbers[5]);
        int port = e * 256 + f;
        return port;
    }

    // Handler for the 'cd' command
    private static void handleChangeDirectory(String command) {
        String[] cdDir = command.trim().split(" ");
        if (!cdDir[0].equals("cd")) {
            System.out.println("0x001 Invalid command.");
        } else if (cdDir.length == 2) {
            String dir = cdDir[1];
            send("CWD " + dir);
            receive(socket);
        } else {
            System.out.println("0x002 Incorrect number of arguments.");
        }
    }

    // Handler for the 'features' command
    private static void handleFeatures(String command) {
        String[] args = command.trim().split(" ");
        if (!args[0].equals("features")) {
            System.out.println("0x001 Invalid Command.");
        } else if (args.length == 1) {
            send("FEAT");
            receive(socket);
        } else {
            System.out.println("0x002 Incorrect number of arguments.");
        }
    }

    // Handler for the 'get REMOTE' command
    private static void handleGet(String command) {
        String[] getArgs = command.trim().split(" ");
        if (!getArgs[0].equals("get")) {
            System.out.println("0x001 Invalid Command.");
        } else if (getArgs.length == 2) {
            String remote = getArgs[1];
            send("PASV");
            String line = receive(socket);
            if (line != null && line.startsWith("227")) {
                getRemoteFile(line, remote);
            }
        } else {
            System.out.println("0x002 Incorrect number of arguments.");
        }
    }

    // Retrieve remote file
    private static void getRemoteFile(String line, String fileName) {
        String host = extractHost(line);
        int port = extractPort(line);
        Socket dataSocket;
        try {
            dataSocket = new Socket(host, port);
        } catch (IOException e) {
            System.out.println("0x3A2 Data transfer connection to " + host + " on port " + port + " failed to open");
//            e.printStackTrace();
            return;
        }
        try {
            send("RETR " + fileName);
            String res = receive(socket);
            if (res != null && (res.startsWith("150") || res.startsWith("125"))) {
                receiveFile(dataSocket, fileName);
                receive(socket);
            }
        } catch(FileNotFoundException fnfe) {
            System.out.println("0x38E Access to local file " + fileName + " denied");
        } catch (IOException e) {
            System.out.println("0x3A7 Data transfer connection I/O error, closing data connection");
        }
        try {
            dataSocket.close();
        } catch (IOException e) {
//            e.printStackTrace();
        }
    }

    // Handler for the 'pw' command
    private static void handlePassword(String command) {
        String[] pwArgs = command.trim().split(" ");
        if (!pwArgs[0].equals("pw")) {
            System.out.println("0x001 Invalid Command.");
        } else if (pwArgs.length == 2) {
            String pw = pwArgs[1];
            send("PASS " + pw);
            receive(socket);
        } else {
            System.out.println("0x002 Incorrect number of arguments.");
        }
    }

    // Handler for the 'user' command
    private static void handleUser(String command) {
        String[] userArgs = command.trim().split(" ");
        if (!userArgs[0].equals("user")) {
            System.out.println("0x001 Invalid Command.");
        } else if (userArgs.length == 2) {
            String userName = userArgs[1];
            send("USER " + userName);
            receive(socket);
        } else {
            System.out.println("0x002 Incorrect number of arguments.");
        }
    }

    // If connected, sends a QUIT to the server, and closes any established connection
    // and then exits the program. This command is valid at any time.
    private static void handleQuit() {
        if (socket.isConnected()) {
            send("QUIT");
            receive(socket);
        }
        try {
            socket.close();
        } catch (IOException e) {
//            e.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    // Send a message to the FTP server
    private static void send(String message) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream());
            out.write(message + "\r\n");
            out.flush();
            System.out.println("--> " + message);
        } catch (IOException e) {
//            e.printStackTrace();
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            try {
                socket.close();
            } catch (IOException ioe) {
//                ioe.printStackTrace();
            } finally {
                System.exit(-1);
            }
        }
    }

    // Receive response from FTP server
    private static String receive(Socket s) {
        return receive(s, true);
    }

    // Receive response from FTP server
    // isControlConnection is true for control connection, false for data connection
    // return the last line received from the server
    private static String receive(Socket s, boolean isControlConnection) {
        try {
            String line;
            if (s.isClosed()) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String responseCode = null;
            while ((line = reader.readLine()) != null) {
                if (isControlConnection) {
                    System.out.println("<-- " + line);
                } else {
                    System.out.println(line);
                }
                if (responseCode == null) {
                    responseCode = line.substring(0, 3); // Extract the response code
                }
                // Identify the last line of response by responseCode followed by a <space>
                if (line.startsWith(responseCode + " ")) break;
            }
            return line;
        } catch (IOException e) {
//            e.printStackTrace();
            if (isControlConnection) {
                System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            } else {
                System.out.println("0x3A7 Data transfer connection I/O error, closing data connection.");
            }
            try {
                s.close();
            } catch (IOException ioe) {
//                ioe.printStackTrace();
            } finally {
                System.exit(-1);
            }
        }
        return null;
    }

    // receive and write a file to local disk
    private static void receiveFile(Socket s, String fileName) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        FileOutputStream out = new FileOutputStream(fileName);
        int data;
        while((data = in.read()) != -1) {
            out.write(data);
        }
        in.close();
        out.close();
        s.close();
    }
}
