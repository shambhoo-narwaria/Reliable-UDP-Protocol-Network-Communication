import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Client class reads requested configurations, connects to a Server, negotiates connection,
 * and accepts a Reliable File Transfer via UDP mimicking Go-Back-N Protocol structures.
 */
public class Client {
    public static void main(String[] args) throws Exception {
        // 1. Read configurations
        File configFile = new File("support/client.in");
        if (!configFile.exists()) {
            System.err.println("Config file support/client.in not found.");
            return;
        }
        
        Scanner scanner = new Scanner(configFile);
        String serverIpAddress = scanner.nextLine().trim();
        int serverPort = Integer.parseInt(scanner.nextLine().trim());
        int clientPort = Integer.parseInt(scanner.nextLine().trim());
        String requestedFileName = scanner.nextLine().trim();
        int windowSize = Integer.parseInt(scanner.nextLine().trim());
        scanner.close();
        
        // 2. Initialize networking sockets
        DatagramSocket socket = new DatagramSocket(clientPort);
        InetAddress serverAddress = InetAddress.getByName(serverIpAddress);
        
        System.out.println("Starting client...");
        System.out.println("Requesting file: " + requestedFileName);
        
        // 3. Ensure "output" folder exists and configure the output file stream
        File outputDir = new File("output");
        if (!outputDir.exists()) outputDir.mkdir(); // Ensure directory exists dynamically
        
        // Snip the folder name out of the requested filename to just save locally by base name
        String outName = requestedFileName;
        if (requestedFileName.contains("/")) {
            outName = requestedFileName.substring(requestedFileName.lastIndexOf("/") + 1);
        }
        File outputFile = new File(outputDir, outName);
        FileOutputStream fos = new FileOutputStream(outputFile, true); // Added 'true' for append mode
        
        // 4. Send initial handshake file name query packet
        Packet namePacket = new Packet(0, requestedFileName.getBytes(), requestedFileName.length());
        byte[] requestBytes = namePacket.toBytes();
        DatagramPacket requestDatagram = new DatagramPacket(requestBytes, requestBytes.length, serverAddress, serverPort);
        socket.send(requestDatagram);
        
        // 5. Wait for handshake response (metadata acknowledging File Size from Server)
        byte[] ackData = new byte[64];
        DatagramPacket ackDatagram = new DatagramPacket(ackData, ackData.length);
        socket.receive(ackDatagram); // Blocks until Server replies
        
        int fileSize = Packet.parseAck(ackDatagram.getData(), ackDatagram.getLength());
        System.out.println("Server responded. File size: " + fileSize + " bytes.");
        
        // 6. Confim preparation (Send Metadata ACK back to start transmission flow)
        byte[] readyAckBytes = Packet.createAck(0);
        socket.send(new DatagramPacket(readyAckBytes, readyAckBytes.length, serverAddress, serverPort));
        
        // 7. Begin main Go-Back-N receiving loop
        int expectedSeq = 0;              // Local expectation tracker (in-order delivery)
        int seqNumCount = 3 * windowSize; // Limits sequence number boundary identically to server
        int receivedSize = 0;             // Tracker metric until match fileSize
        
        System.out.println("\u001B[36m[START]\u001B[0m Ready to receive file via Go-Back-N...");
        
        while (receivedSize < fileSize) {
            byte[] recvBuffer = new byte[1024];
            DatagramPacket dataPacket = new DatagramPacket(recvBuffer, recvBuffer.length);
            socket.receive(dataPacket); // Block until next data payload
            
            Packet p = new Packet(dataPacket.getData(), dataPacket.getLength());
            
            // DATA INTEGRITY PIPELINE
            // Step 1: Detect raw corruption via mathematically matching CRC32-generated checksums
            if (!p.isNotCorrupt()) {
                System.out.println("\u001B[31m[CORRUPT]\u001B[0m Checksum mismatch. Dropping packet " + p.seqno);
                continue; // Ignoring corruption forces the Server into a Timeout-Retransmit
            }
            
            // Step 2: Ensure correct sequential ordering (we only receive in-order in GBN)
            if (p.seqno == expectedSeq) {
                System.out.println("\u001B[32m[RECV]\u001B[0m Expected Sequence " + expectedSeq + " received.");
                
                // Write payload segment to disk output file stream
                fos.write(p.data);
                receivedSize += p.data.length;
                
                // Generate and dispatch an acknowledgement referencing the current expected Sequence
                byte[] ackBytes = Packet.createAck(expectedSeq);
                socket.send(new DatagramPacket(ackBytes, ackBytes.length, serverAddress, serverPort));
                System.out.println("\u001B[34m[ACK]\u001B[0m Acknowledgement sent for " + expectedSeq);
                
                // Proceed standard iteration loop expectation tracker
                expectedSeq = (expectedSeq + 1) % seqNumCount;
                
            } else {
                // An out-of-order sequence arrived. e.g. We expected Seq 2, but received Seq 3 (due to Packet 2 dropping naturally)
                System.out.println("\u001B[33m[OUT-ORDER]\u001B[0m Received " + p.seqno + ", expected " + expectedSeq + ". Dropping & re-acking.");
                
                // GBN logic: We re-ACK the last successfully received in-order packet.
                // This implicitly tells Server "Hey, I'm stuck here, start blindly sending again from this anchor!"
                int lastAck = (expectedSeq - 1 + seqNumCount) % seqNumCount;
                byte[] ackBytes = Packet.createAck(lastAck);
                socket.send(new DatagramPacket(ackBytes, ackBytes.length, serverAddress, serverPort));
            }
        }
        
        // Pipeline completed & cleaned
        System.out.println("\u001B[32m[SUCCESS]\u001B[0m File Transfer Complete! Check the 'output' directory.");
        fos.close();
        socket.close();
    }
}
