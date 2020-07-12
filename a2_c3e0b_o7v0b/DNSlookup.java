import exception.NotResponseException;
import exception.RCODEException;
import exception.TransactionIDException;

import javax.xml.bind.DatatypeConverter;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DNS address resolver client
 */

/**
 * @author Donald Acton
 *         This example is adapted from Kurose & Ross
 *         Feel free to modify and rearrange code as you see fit
 */
public class DNSlookup {


    static final int MIN_PERMITTED_ARGUMENT_COUNT = 2;
    static final int MAX_PERMITTED_ARGUMENT_COUNT = 3;
    static final int TIMEOUT = 5000; // timeout = 5 seconds
    static final int MAX_NUMBER_OF_QUERIES = 30;

    static boolean tracingOn = false;
    static boolean IPV6Query = false;

    static String fqdn; // the FQDN for which to look up address
    static InetAddress rootNameServer; // IP address of root name server

    // Records of the response sections:
    static List<Record> answers = new ArrayList<Record>();
    static List<Record> nameservers = new ArrayList<Record>();
    static List<Record> additional = new ArrayList<Record>();

    // CNAMEs of fqdn
    static List<String> cnames = new ArrayList<String>();

    // Number of issued queries
    static int numberOfQueries = 0;

    /**
     * Entry point of the program
     *
     * @param args command line arguments, see usage below
     */
    public static void main(String[] args) throws Exception {
        DNSResponse response; // Just to force compilation
        int argCount = args.length;

        if (argCount < MIN_PERMITTED_ARGUMENT_COUNT || argCount > MAX_PERMITTED_ARGUMENT_COUNT) {
            usage();
            return;
        }

        fqdn = args[1];
        try {
            rootNameServer = InetAddress.getByName(args[0]);
        } catch (UnknownHostException e) {
            printAnswer(new Record(DNSlookup.fqdn, -4, "A", "0.0.0.0"));
            return;
        }

        if (argCount == 3) {  // option provided
            if (args[2].equals("-t"))
                tracingOn = true;
            else if (args[2].equals("-6"))
                IPV6Query = true;
            else if (args[2].equals("-t6")) {
                tracingOn = true;
                IPV6Query = true;
            } else { // option present but wasn't valid option
                usage();
                return;
            }
        }

        // Start adding code here to initiate the lookup
        List<Record> ret = DNSlookUp(rootNameServer, fqdn, IPV6Query);
        if (ret.size() > 0) {
            for (Record record : ret) {
                printAnswer(record);
            }
        }
    }

    /**
     * Print answer record
     *
     * @param record an answer record to print
     */
    private static void printAnswer(Record record) {
        System.out.println(DNSlookup.fqdn + " " + record.ttl + "   " + record.recordType + " " + record.recordValue);
    }

    /**
     * Perform DNS look up
     *
     * @param fqdn          the domain name to look up
     * @param serverAddress address of the DNS server
     * @param isIPv6        true for IPv6 query, false for IPv4 query
     * @return the answer records
     */
    private static List<Record> DNSlookUp(InetAddress serverAddress, String fqdn, boolean isIPv6) throws Exception {
        // return value
        List<Record> ans = new ArrayList<Record>();

        // get a datagram socket
        DatagramSocket socket = new DatagramSocket();

        // send request
        byte[] domainNameBuffer = compressDomainName(fqdn);

        byte[] buf = new byte[1024];
        int transactionId = setUpQuery(domainNameBuffer, buf, isIPv6);

        // 12 is header length, 4 is QCLASS and QTYPE length
        int dnsQueryLength = domainNameBuffer.length + 12 + 4;

        DatagramPacket packet = new DatagramPacket(buf, dnsQueryLength, serverAddress, 53);
        if (tracingOn) {
            printQueryInfo(serverAddress, fqdn, transactionId, isIPv6);
        }
        socket.setSoTimeout(TIMEOUT);
        socket.send(packet);
        numberOfQueries++;
        if (numberOfQueries > MAX_NUMBER_OF_QUERIES) {
            // Too many queries issued
            Record record = new Record(DNSlookup.fqdn, -3, "A", "0.0.0.0");
            ans.add(record);
            return ans;
        }
        // get response
        packet = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException se) {
            if (tracingOn) {
                printQueryInfo(serverAddress, fqdn, transactionId, isIPv6);
            }
            // if timed out, resend
            packet = new DatagramPacket(buf, dnsQueryLength, serverAddress, 53);
            socket.send(packet);
            try {
                packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
            } catch (SocketTimeoutException se2) {
                // if still don't get response, indicate that the name can't be looked up
                printAnswer(new Record(DNSlookup.fqdn, -2, "A", "0.0.0.0"));
                return ans;
            }
        }

        byte[] received = packet.getData();

        boolean isAuthoritative = false;
        // decode response
        try {
            isAuthoritative = decodeResponse(transactionId, received, dnsQueryLength);
        } catch (TransactionIDException te) {
            // If transaction id is different, repeat receive call and wait for another response
            socket.receive(packet);
            received = packet.getData();
            isAuthoritative = decodeResponse(transactionId, received, dnsQueryLength);
        } catch (RCODEException re) {
            switch (re.getRCODE()) {
                case 3:
                    printAnswer(new Record(DNSlookup.fqdn, -1, "A", "0.0.0.0"));
                    break;
                default:
                    printAnswer(new Record(DNSlookup.fqdn, -4, "A", "0.0.0.0"));
                    break;
            }
            return ans;
        } catch (NotResponseException ne) {
            printAnswer(new Record(DNSlookup.fqdn, -4, "A", "0.0.0.0"));
            return ans;
        }
        if (answers.size() > 0 || isAuthoritative) {
            if (answers.size() == 0) { // No answer, but authoritative SOA
                for (Record r : nameservers) {
                    // replace nameserver data with default data
                    r.ttl = -6;
                    r.recordType = "A";
                    r.recordValue = "0.0.0.0";
                }
                return new ArrayList<Record>(nameservers);
            }
            Record first = answers.get(0);
            if (first.recordType.equals("CN")) {
                String cName = first.recordValue;
                cnames.add(cName);
                clearRecords();
                return DNSlookUp(DNSlookup.rootNameServer, cName, IPV6Query);
            } else {
                for (Record record : answers) {
                    if (!record.recordName.equals(DNSlookup.fqdn)) {
                        if (record.recordType.equals("A") && !cnames.contains(fqdn)) {
                            answers.clear();
                            return DNSlookUp(InetAddress.getByName(record.recordValue), DNSlookup.fqdn, IPV6Query);
                        }
                    }
                }
                ans = new ArrayList<Record>(answers);
                answers.clear();
            }
        } else {
            String nameServerAddress = null;
            if (nameservers.size() > 0) {
                Record NS = nameservers.get(0); // use first entry
                for (Record r : additional) {
                    if (NS.recordValue.equals(r.recordName) && r.recordType.equals("A")) {
                        nameServerAddress = r.recordValue;
                        break;
                    }
                }
                clearRecords();
                if (nameServerAddress == null) {
                    // if no 'Additional Info' section exists for NS, use root name server to resolve NS IP address
                    return DNSlookUp(DNSlookup.rootNameServer, NS.recordValue, false);
                } else {
                    // otherwise use NS to resolve IP address of fqdn
                    boolean version; // is IPv6 or not
                    if (fqdn.equals(DNSlookup.fqdn) || cnames.contains(fqdn)) {
                        version = IPV6Query;
                    } else {
                        // do IPv4 address lookup when getting address for a nameserver
                        version = false;
                    }
                    return DNSlookUp(InetAddress.getByName(nameServerAddress), fqdn, version);
                }
            }
        }
        socket.close();
        return ans;
    }

    /**
     * Clear the records from last response
     */
    private static void clearRecords() {
        answers.clear();
        additional.clear();
        nameservers.clear();
    }

    /**
     * Set up the header, QNAME, QCLASS, and QTYPE sections of the query
     *
     * @param domainNameBuffer buffer of the fqdn
     * @param buf              buffer of the query
     * @param isIPv6           true for IPv6 queries, false for IPv4 queries
     * @return randomly generated transaction ID of the query
     */
    private static int setUpQuery(byte[] domainNameBuffer, byte[] buf, boolean isIPv6) {
        int transactionId = setQueryHeader(buf, false); // false for non-recursive queries
        setQueryQNAME(buf, domainNameBuffer);
        setQueryTypeAndClass(buf, domainNameBuffer.length, isIPv6);
        return transactionId;
    }

    /**
     * Display query information
     *
     * @param serverAddress DNS server address
     * @param fqdn          domain name
     * @param transactionId transaction ID of query
     */
    private static void printQueryInfo(InetAddress serverAddress, String fqdn, int transactionId, boolean isIPv6) {
        System.out.printf("\n\n");
        System.out.print("Query ID     " + transactionId + " ");
        System.out.print(fqdn + "  ");
        System.out.print(isIPv6 ? "AAAA" : "A" + " ");
        System.out.println(" --> " + serverAddress.getHostAddress());
    }

    /**
     * Decode the response from a DNS server
     *
     * @param received       the received data
     * @param dnsQueryLength the length of the query sent to the server
     * @return true if answer is authoritative, false otherwise
     * @throws TransactionIDException if transaction id don't match
     * @throws RCODEException         if RCODE in response is not 0
     * @throws NotResponseException   if first bit in flag is not 1
     */
    private static boolean decodeResponse(int transactionId, byte[] received, int dnsQueryLength) throws Exception {
        int ID = ((received[0] << 8) & 0xff00) | (received[1] & 0xff);
        if (transactionId != ID) throw new TransactionIDException();
        // Decode RCODE
        int RCODE = received[3] & 0xf;
        if (RCODE != 0) {
            throw new RCODEException(RCODE);
        }
        // Decode answer count, NS count, additional info count
        int ANCOUNT = ((received[6] << 8) & 0xff00) | (received[7] & 0xff);
        int NSCOUNT = ((received[8] << 8) & 0xff00) | (received[9] & 0xff);
        int ARCOUNT = ((received[10] << 8) & 0xff00) | (received[11] & 0xff);

        boolean isResponse = ((received[2] & 0x80) >>> 7) == 1; // first bit of flags
        if (!isResponse) throw new NotResponseException();
        boolean isAuthoritative = ((received[2] & 0x4) >>> 2) == 1;
        if (tracingOn) {
            System.out.printf("Response ID: %d Authoritative = %b\n", transactionId, isAuthoritative);
        }

        byte[] answer = new byte[1024];
        for (int i = 0; i + dnsQueryLength < received.length; i++) {
            answer[i] = received[dnsQueryLength + i];
        }
        int ptr = 0; // starting index of the current Resource Record in answer
        for (int k = 0; k < ANCOUNT + NSCOUNT + ARCOUNT; k++) {
            if (tracingOn) {
                if (k == 0) {
                    System.out.printf("  Answers %d\n", ANCOUNT);
                }
                if (k == ANCOUNT) {
                    System.out.printf("  Nameservers %d\n", NSCOUNT);
                }
                if (k == ANCOUNT + NSCOUNT) {
                    System.out.printf("  Additional Information %d\n", ARCOUNT);
                }
            }
            int recordTypeCode = ((answer[ptr + 2] << 8) & 0xff00) | (answer[ptr + 3] & 0xff);
            int qclass = (answer[ptr + 4] << 8 & 0xff00) | (answer[ptr + 5] & 0xff);
            int ttl = answer[ptr + 6] << 24 & 0xff000000;
            ttl |= answer[ptr + 7] << 16 & 0xff0000;
            ttl |= answer[ptr + 8] << 8 & 0xff00;
            ttl |= answer[ptr + 9] & 0xff;
            int dataLength = ((answer[ptr + 10] << 8) & 0xff00) | (answer[ptr + 11] & 0xff);

            String recordName;
            // the starting location of the record name inside received
            int location = ptr; // by default(pointer is not used), location is at ptr
            // if using pointer for compression (first 2 bits are 1's)
            if (isPointerUsed(answer[ptr])) {
                location = answer[ptr + 1] & 0xff;
                location |= ((answer[ptr] & 0x3f) << 8);
            }
            recordName = getNameServerValue(received, location).substring(1);

            String recordValue = "";
            String recordType = "";

            byte[] ip;
            switch (recordTypeCode) {
                case 1: // A
                    ip = new byte[4];
                    System.arraycopy(answer, ptr + 12, ip, 0, 4);
                    recordValue = getIPv4Address(ip);
                    recordType = "A";
                    break;
                case 2: // NS
                    recordType = "NS";
                    recordValue = getNameServerValue(received, ptr + 12 + dnsQueryLength).substring(1);
                    break;
                case 5: // CNAME
                    recordType = "CN";
                    recordValue = getNameServerValue(received, ptr + 12 + dnsQueryLength).substring(1);
                    break;
                case 6: // SOA
                    recordType = "6";
                    recordValue = "----";
                    break;
                case 28:// AAAA
                    ip = new byte[16];
                    System.arraycopy(answer, ptr + 12, ip, 0, 16);
                    recordValue = getIPv6Address(ip);
                    recordType = "AAAA";
                    break;
                default:
                    break;
            }
            Record r = new Record(recordName, ttl, recordType, recordValue);
            if (k < ANCOUNT) {
                answers.add(r);
            } else if (k < ANCOUNT + NSCOUNT) {
                nameservers.add(r);
            } else if (k >= ANCOUNT + NSCOUNT) {
                additional.add(r);
            }
            if (tracingOn) {
                System.out.format("       %-30s %-10d %-4s %s\n", recordName, ttl, recordType, recordValue);
                if (k == ANCOUNT - 1 && NSCOUNT == 0 && ARCOUNT == 0) {
                    System.out.printf("  Nameservers %d\n", 0);
                }
                if (k == ANCOUNT + NSCOUNT - 1 && ARCOUNT == 0) {
                    System.out.printf("  Additional Information %d\n", 0);
                }
            }
            ptr = ptr + 12 + dataLength;
        }
        return isAuthoritative;
    }


    /***
     * Record class of useful information
     */
    static class Record {
        String recordName;
        int ttl;
        String recordType;
        String recordValue;

        public Record(String recordName, int ttl, String recordType, String recordValue) {
            this.recordName = recordName;
            this.ttl = ttl;
            this.recordType = recordType;
            this.recordValue = recordValue;
        }

        @Override
        public String toString() {
            return "Record{" +
                    "recordName='" + recordName + '\'' +
                    ", ttl=" + ttl +
                    ", recordType='" + recordType + '\'' +
                    ", recordValue='" + recordValue + '\'' +
                    '}';
        }
    }

    /**
     * Decode the record name or NS value
     *
     * @param received the received response
     * @param location the starting location of the record name/NS inside received
     * @return record name
     */
    private static String getNameServerValue(byte[] received, int location) {
        String name;
        int nameLength = 0;
        while (received[nameLength + location] != 0) {
            nameLength++; // compute length of the record name
        }
        byte[] converted = new byte[nameLength];
        System.arraycopy(received, location, converted, 0, nameLength);
        name = decompressDomainName(received, converted);
        return name;
    }

    /**
     * Pretty print an array of bytes
     */
    private static String dump(byte[] bytes) {
        String hex = DatatypeConverter.printHexBinary(bytes);
        System.out.println(hex);
        return hex;
    }

    /**
     * Get the IPv4 format address from byte array
     *
     * @param ip the byte array data, length = 4
     * @return the IPv4 address
     */
    private static String getIPv4Address(byte[] ip) {
        if (ip.length != 4) return null;
        String address = "";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < ip.length; j++) {
            sb.append(byteToInt(ip[j]));
            if (j == ip.length - 1) {
                address = sb.toString();
            } else {
                sb.append(".");
            }
        }
        return address;
    }

    /**
     * Get the ipv6 format address from byte array
     *
     * @param ip the byte array data, length = 16
     * @return the ipv6 address
     */
    private static String getIPv6Address(byte[] ip) {
        if (ip.length != 16) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ip.length; i += 2) {
            byte first = ip[i];
            byte second = ip[i + 1];
            int data = ((first & 0xff) << 8) | (second & 0xff);
            String b = Integer.toString(data, 16);
            sb.append(b);
            sb.append(":");
        }
        sb.deleteCharAt(sb.lastIndexOf(":"));
        return sb.toString();
    }

    /**
     * Convert an unsigned byte to an int
     *
     * @param b the byte to be converted
     * @return the extended int
     */
    private static int byteToInt(byte b) {
        return b & 0xff;
    }

    /**
     * Set the header of query, header is 12 bytes long
     *
     * @param buf         buffer of query
     * @param isRecursive true if the query is recursive, false if iterative
     * @return the transaction ID
     */
    private static int setQueryHeader(byte[] buf, boolean isRecursive) {
        // Transaction ID
        Random generator = new Random();
        int random = generator.nextInt(65536);
        buf[0] = (byte) ((random >> 8) & 0xff);
        buf[1] = (byte) (random & 0xff);
        // Flags
        if (isRecursive) {
            buf[2] = 0x01;
        } else {
            buf[2] = 0x00;
        }
        buf[3] = 0x00;
        // Questions (QDCOUNT)
        buf[4] = 0x00;
        buf[5] = 0x01;
        // Answer RR (ANCOUNT)
        buf[6] = 0x00;
        buf[7] = 0x00;
        // Authority RR (NSCOUNT)
        buf[8] = 0x00;
        buf[9] = 0x00;
        //  Additional RR (ARCOUNT)
        buf[10] = 0x00;
        buf[11] = 0x00;
        return random;
    }

    /**
     * Set QNAME of query
     *
     * @param buf           buffer for the query
     * @param addressBuffer buffer for QNAME
     */
    private static void setQueryQNAME(byte[] buf, byte[] addressBuffer) {
        for (int i = 0; i < addressBuffer.length; i++) {
            buf[i + 12] = addressBuffer[i];
        }
    }

    /**
     * Set QTYPE and QCLASS of query
     *
     * @param buf    buffer for the query
     * @param len    length of QNAME
     * @param isIPv6 false if IPv4, true if IPv6
     */
    private static void setQueryTypeAndClass(byte[] buf, int len, boolean isIPv6) {
        // QTYPE
        // https://en.wikipedia.org/wiki/List_of_DNS_record_types
        if (!isIPv6) {
            buf[12 + len] = 0x00;
            buf[13 + len] = 0x01;  // QTYPE=A    id: 1
        } else {
            buf[12 + len] = 0x00;
            buf[13 + len] = 0x1C;  // QTYPE=AAAA id: 28
        }
        // QCLASS
        buf[14 + len] = 0x00;
        buf[15 + len] = 0x01;    // QTYPE=IN 1 the Internet
    }

    /**
     * Convert domain name format
     * Example:
     * www.google.com -> 3www6google3com0
     * www.cs.ubc.ca -> 3www2cs3ubc2ca0
     *
     * @param domainName the domain name to convert
     * @return the converted byte array
     */
    private static byte[] compressDomainName(String domainName) {
        String[] subdomains = domainName.split("\\.");
        int[] domainLengths = new int[subdomains.length];
        for (int i = 0; i < subdomains.length; i++) {
            domainLengths[i] = subdomains[i].length();
        }
        byte[] result = new byte[domainName.length() + 2];
        int ptr = 0;
        for (int i = 0; i < subdomains.length; i++) {
            String subdomain = subdomains[i];
            int len = domainLengths[i];
            result[ptr++] = (byte) len;
            byte[] data = subdomain.getBytes();
            for (int j = 0; j < data.length; j++) {
                result[ptr++] = data[j];
            }
        }
        return result;
    }

    /**
     * Recursively convert back compressed domain names
     * Example: 3www6google3com0 -> www.google.com
     *
     * @param converted the converted domain name
     * @return original domain name
     */
    private static String decompressDomainName(byte[] received, byte[] converted) {
//        System.out.println("convert back " + DatatypeConverter.printHexBinary(converted));
        if (converted.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < converted.length) {
            byte b = converted[i];
            if (isPointerUsed(b)) {
                // follow pointer
                int location;
                location = converted[i + 1] & 0xff;
                location |= ((b & 0x3f) << 8);
                return sb.toString() + getNameServerValue(received, location); // recursion
            } else {
                int step = converted[i++];
                byte[] sub = new byte[step];
                System.arraycopy(converted, i, sub, 0, step);
                sb.append(".").append(new String(sub));
                i += step;
            }
        }
        return sb.toString();
    }

    /**
     * Determine whether pointer is used in message compression
     * True if the first two bits in the octet are one's
     *
     * @param b the byte to test
     * @return true if pointer used in compression
     */
    private static boolean isPointerUsed(byte b) {
        return ((b & 0xff) >>> 6) == 0x3;
    }

    /**
     * Show usage of the program
     */
    private static void usage() {
        System.out.println("Usage: java -jar DNSlookup.jar rootDNS name [-6|-t|t6]");
        System.out.println("   where");
        System.out.println("       rootDNS - the IP address (in dotted form) of the root");
        System.out.println("                 DNS server you are to start your search at");
        System.out.println("       name    - fully qualified domain name to lookup");
        System.out.println("       -6      - return an IPV6 address");
        System.out.println("       -t      - trace the queries made and responses received");
        System.out.println("       -t6     - trace the queries made, responses received and return an IPV6 address");
    }
}


