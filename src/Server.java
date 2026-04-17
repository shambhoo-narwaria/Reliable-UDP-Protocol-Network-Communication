import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Server class handles incoming UDP connections, processing file transfer requests,
 * and transmitting the file reliably using the Go-Back-N (GBN) windowing protocol.
 */
public class Server {
    // Defines the amount of elapsed time before an unacknowledged window triggers a retransmission
    private static final int TIMEOUT_MS = 500;
    
    public static void main(String[] args) throws Exception {
        // 1. Read configuration settings from the central config file
        File configFile = new File("support/server.in");
        if (!configFile.exists()) {
            System.err.println("Config file support/server.in not found.");
            return;
        }
        
        Scanner scanner = new Scanner(configFile);
        int serverPort = Integer.parseInt(scanner.nextLine().trim());
        int maxWindowSize = Integer.parseInt(scanner.nextLine().trim());
        int seed = Integer.parseInt(scanner.nextLine().trim());
        double plp = Double.parseDouble(scanner.nextLine().trim()); // Packet Loss Probability simulation
        scanner.close();
        
        // 2. Initialize Networking & RNG constructs
        Random random = new Random(seed);
        DatagramSocket socket = new DatagramSocket(serverPort);
        System.out.println("\u001B[36m[INIT]\u001B[0m Server started on port " + serverPort);
        
        byte[] receiveData = new byte[1024];
        
        // 3. Continuously listen and serve clients in a loop
        while (true) {
            System.out.println("Waiting for file connection...");
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);
            
            // Extract request details
            InetAddress clientAddress = receivePacket.getAddress();
            int clientPort = receivePacket.getPort();
            
            Packet requestPacket = new Packet(receiveData, receivePacket.getLength());
            String requestedFileName = new String(requestPacket.data).trim();
            System.out.println("Requested File: " + requestedFileName);
            
            // 4. Validate requested file
            File requestedFile = new File(requestedFileName);
            if (!requestedFile.exists()) {
                System.err.println("Requested file " + requestedFileName + " does not exist.");
                continue;
            }
            
            long fileSize = requestedFile.length();
            
            // 5. Send file size as a metadata handshake before the data dump
            byte[] ackData = Packet.createAck((int) fileSize);
            DatagramPacket sendSizePacket = new DatagramPacket(ackData, ackData.length, clientAddress, clientPort);
            socket.send(sendSizePacket);
            
            // Wait for client to acknowledge they're ready to start receiving
            socket.receive(receivePacket);
            
            System.out.println("\u001B[32m[READY]\u001B[0m File transmission started (Go-Back-N).");
            
            // 6. Begin reliable transmission using Go-Back-N protocol
            socket.setSoTimeout(TIMEOUT_MS);
            gbn(socket, clientAddress, clientPort, requestedFile, maxWindowSize, random, plp);
            socket.setSoTimeout(0); // Reset timeout back to infinite for next client
            
            System.out.println("\u001B[32m[SUCCESS]\u001B[0m Served file " + requestedFileName + " successfully.\n");
        }
    }
    
    /**
     * Executes the Go-Back-N Reliable UDP transmission algorithm.
     * @param socket Current networking DatagramSocket
     * @param clientAdd Destination Client IP Address
     * @param clientPort Destination Client Port Number
     * @param file The literal file we are streaming back
     * @param wSize The predefined window size for Go-Back-N
     * @param rnd The RNG system for generating synthetic packet drops
     * @param plp Probability of dropping a packet (used to demo protocol robustness)
     */
    private static void gbn(DatagramSocket socket, InetAddress clientAdd, int clientPort, File file, int wSize, Random rnd, double plp) throws IOException {
        long fileSize = file.length();
        int base = 0;              // Oldest unacknowledged sequence number
        int nextSeq = 0;           // Sequence number for the next outgoing packet
        int seqNumCount = 3 * wSize; // Maximum distinct sequence numbers needed to avoid aliasing
        
        FileInputStream fis = new FileInputStream(file);
        Packet[] windowPackets = new Packet[seqNumCount]; // Buffer to hold our current window's sliding packets
        
        while (base * Packet.DATA_SIZE < fileSize) {
            
            // 1. Send permitted packets within the current window frame
            while (nextSeq < base + wSize && nextSeq * Packet.DATA_SIZE < fileSize) {
                byte[] fileData = new byte[Packet.DATA_SIZE];
                
                // Jump to precise position in file via NIO channel offset instead of storing entire file in memory
                fis.getChannel().position((long) nextSeq * Packet.DATA_SIZE);
                int bytesRead = fis.read(fileData);
                
                // Create packet, place it in sliding window buffer 
                Packet p = new Packet(nextSeq % seqNumCount, fileData, bytesRead);
                windowPackets[nextSeq % seqNumCount] = p;
                
                // Simulate Packet Loss
                if (rnd.nextDouble() > plp) {
                    byte[] rawPacket = p.toBytes();
                    DatagramPacket datagram = new DatagramPacket(rawPacket, rawPacket.length, clientAdd, clientPort);
                    socket.send(datagram);
                    System.out.println("\u001B[32m[SEND]\u001B[0m Packet " + p.seqno + " sent");
                } else {
                    // Packet was "Dropped" arbitrarily by Server to demonstrate loss over the network!
                    System.out.println("\u001B[31m[DROP]\u001B[0m Simulating network drop for packet " + p.seqno);
                }
                nextSeq++;
            }
            
            // 2. Await Client Acknowledgements
            try {
                byte[] ackBuff = new byte[64];
                DatagramPacket ackDatagram = new DatagramPacket(ackBuff, ackBuff.length);
                
                // This call blocks up to TIMEOUT_MS - it will throw SocketTimeoutException if no ACKs arrive!
                socket.receive(ackDatagram);
                
                int ackNo = Packet.parseAck(ackBuff, ackDatagram.getLength());
                System.out.println("\u001B[34m[ACK]\u001B[0m Received ACK " + ackNo);
                
                // Slide window forward dynamically based on Cumulative ACK processing
                if (ackNo >= (base % seqNumCount)) {
                    base += (ackNo - (base % seqNumCount)) + 1;
                } else if (ackNo < (base % seqNumCount) && (base % seqNumCount) > seqNumCount / 2) {
                    // Handle numeric wrap-around scenarios implicitly via module arithmetic limits
                    base += (seqNumCount - (base % seqNumCount)) + ackNo + 1;
                }
                
            } catch (SocketTimeoutException e) {
                // 3. Timeout Triggered = Go Back N Mechanism Kick-in
                System.out.println("\u001B[33m[TIMEOUT]\u001B[0m Timeout, retransmitting window from base " + base);
                nextSeq = base; // Force nextSeq back to our base, which forces the while loops to resend old data
            }
        }
        fis.close();
    }
}
