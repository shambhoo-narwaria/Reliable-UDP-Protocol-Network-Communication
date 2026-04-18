import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Client — Reliable UDP File Client supporting three windowing protocols:
 *
 *   • Stop-and-Wait (sw)    – Expects one packet at a time; ACKs each before next.
 *   • Go-Back-N     (gbn)   – Accepts only in-order packets; re-ACKs last good on out-of-order.
 *   • Selective Repeat (sr) – Buffers out-of-order packets; ACKs each individually.
 *
 * Usage (both args optional — fall back to client.in defaults):
 *   java -cp bin Client                           → uses config file defaults
 *   java -cp bin Client input/myfile.txt          → custom file, default protocol (gbn)
 *   java -cp bin Client input/myfile.txt sr       → custom file + Selective Repeat
 *
 * Config: support/client.in  (see inline comments in that file)
 */
public class Client {

    // ── Entry Point ──────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        // 1. Load baseline configuration from support/client.in
        File configFile = new File("support/client.in");
        if (!configFile.exists()) {
            System.err.println("[ERROR] support/client.in not found.");
            return;
        }
        Scanner sc = new Scanner(configFile);
        String serverIp        = sc.nextLine().split("#")[0].trim();
        int    serverPort      = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        int    clientPort      = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        String requestedFile   = sc.nextLine().split("#")[0].trim();
        int    windowSize      = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        sc.close();

        // 2. Allow command-line overrides
        if (args.length > 0) requestedFile = args[0];
        String protocolOverride = (args.length > 1) ? args[1].toLowerCase().trim() : null;

        if (protocolOverride != null && !protocolOverride.equals("gbn") && !protocolOverride.equals("sw") && !protocolOverride.equals("sr")) {
            System.err.println("[ERROR] Unknown protocol '" + protocolOverride + "'. Use: gbn | sw | sr");
            return;
        }

        printBanner(serverIp, serverPort, clientPort, requestedFile, (protocolOverride != null ? protocolOverride : "AUTO"), windowSize);

        // 3. Create UDP socket bound to clientPort
        DatagramSocket socket = new DatagramSocket(clientPort);
        InetAddress    serverAddress = InetAddress.getByName(serverIp);

        // 4. Ensure output directory exists
        File outputDir = new File("output");
        if (!outputDir.exists()) outputDir.mkdir();

        String outName = requestedFile.contains("/")
                ? requestedFile.substring(requestedFile.lastIndexOf("/") + 1)
                : requestedFile;
        // Append mode (true) — subsequent downloads do not overwrite previous content
        FileOutputStream fos = new FileOutputStream(new File(outputDir, outName), true);

        // ── Handshake ────────────────────────────────────────────────────────
        // Step 1: Send file-name request to server
        Packet req = new Packet(0, requestedFile.getBytes(), requestedFile.length());
        byte[] reqBytes = req.toBytes();
        socket.send(new DatagramPacket(reqBytes, reqBytes.length, serverAddress, serverPort));
        System.out.println(Packet.CLR_CYAN + "[HANDSHAKE] File request sent → " + requestedFile + Packet.CLR_RESET);

        // Step 2: Receive handshake metadata from server's ephemeral reply port
        byte[]         handshakeBuf = new byte[Packet.HEADER_SIZE + 64];
        DatagramPacket hPacket      = new DatagramPacket(handshakeBuf, handshakeBuf.length);
        socket.receive(hPacket);

        Packet hReply = new Packet(handshakeBuf, hPacket.getLength());
        String hData  = new String(hReply.data).trim();
        
        // Parse "fileSize:protocol"
        int fileSize = Integer.parseInt(hData.split(":")[0]);
        String serverProtocol = hData.split(":")[1];
        
        // Auto-negotiate: use server's protocol unless the user explicitly forced one in CLI
        String activeProtocol = (protocolOverride != null) ? protocolOverride : serverProtocol;

        InetAddress      activeAddr = hPacket.getAddress(); // Ephemeral server socket address
        int              activePort = hPacket.getPort();    // Ephemeral server socket port!

        System.out.println(Packet.CLR_CYAN + "[HANDSHAKE] Server ready. File size: "
                + fileSize + " bytes | Protocol: " + activeProtocol.toUpperCase() + Packet.CLR_RESET);

        // Step 3: Send "ready" ACK — must go to activePort, not the original serverPort!
        byte[] readyAck = Packet.createAck(0);
        socket.send(new DatagramPacket(readyAck, readyAck.length, activeAddr, activePort));

        // ── Transfer ─────────────────────────────────────────────────────────
        long startTime = System.currentTimeMillis();

        // stats[0] = corrupt packets dropped,  stats[1] = out-of-order dropped (GBN/SW)
        int[] stats = {0, 0};

        switch (activeProtocol) {
            case "sw" -> receiveStopAndWait(socket, activeAddr, activePort, fos, fileSize, windowSize, stats);
            case "sr" -> receiveSelectiveRepeat(socket, activeAddr, activePort, fos, fileSize, windowSize, stats);
            default   -> receiveGoBackN(socket, activeAddr, activePort, fos, fileSize, windowSize, stats);
        }

        long   elapsed    = System.currentTimeMillis() - startTime;
        double throughput = (fileSize / 1024.0) / Math.max(elapsed / 1000.0, 0.001);

        fos.close();
        socket.close();
        printStats(activeProtocol, fileSize, elapsed, throughput, stats[0], stats[1], outName);
    }

    // ── Protocol Receivers ───────────────────────────────────────────────────

    /**
     * GO-BACK-N RECEIVER
     *
     * Behaviour:
     *  • Only accepts the NEXT expected packet in sequence.
     *  • Any out-of-order packet is silently discarded.
     *  • Re-ACKs the last correctly received packet to signal the server to retransmit.
     *  • Simple receiver — no buffering needed.
     */
    private static void receiveGoBackN(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                        FileOutputStream fos, int fileSize, int windowSize, int[] stats)
            throws IOException {

        int expectedSeq  = 0;
        int seqCount     = 3 * windowSize;
        int receivedSize = 0;

        System.out.println(Packet.CLR_CYAN + "[START] Go-Back-N receiver ready." + Packet.CLR_RESET);

        while (receivedSize < fileSize) {
            Packet p = receivePacket(socket);
            if (p == null) { stats[0]++; continue; }   // Corrupt packet → drop

            if (p.seqno == expectedSeq) {
                // In-order: write to file and advance
                fos.write(p.data);
                receivedSize += p.data.length;
                log(Packet.CLR_GREEN, "GBN-RECV", String.format("Seq %-3d  │ %d bytes  │ %s",
                        expectedSeq, p.data.length, progressBar(receivedSize, fileSize)));
                sendAck(socket, serverAddr, serverPort, expectedSeq);
                expectedSeq = (expectedSeq + 1) % seqCount;

            } else {
                // Out-of-order: discard and re-ACK the last good packet
                int lastGood = (expectedSeq - 1 + seqCount) % seqCount;
                stats[1]++;
                log(Packet.CLR_YELLOW, "GBN-DISCARD",
                        String.format("Got %-3d  expected %-3d → re-ACK %d", p.seqno, expectedSeq, lastGood));
                sendAck(socket, serverAddr, serverPort, lastGood);
            }
        }
        System.out.println(Packet.CLR_GREEN + "[DONE] Go-Back-N transfer complete." + Packet.CLR_RESET);
    }

    /**
     * STOP-AND-WAIT RECEIVER
     *
     * Behaviour:
     *  • Expects alternating bit sequence numbers (0, 1, 0, 1 …).
     *  • ACKs every in-order packet immediately.
     *  • Discards duplicate/old packets but still ACKs them (handles lost ACKs).
     */
    private static void receiveStopAndWait(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                            FileOutputStream fos, int fileSize, int windowSize, int[] stats)
            throws IOException {

        int expectedSeq  = 0;
        int receivedSize = 0;

        System.out.println(Packet.CLR_CYAN + "[START] Stop-and-Wait receiver ready." + Packet.CLR_RESET);

        while (receivedSize < fileSize) {
            Packet p = receivePacket(socket);
            if (p == null) { stats[0]++; continue; }

            if (p.seqno == expectedSeq) {
                // Correct packet — write and flip expected bit
                fos.write(p.data);
                receivedSize += p.data.length;
                log(Packet.CLR_GREEN, "SW-RECV", String.format("Seq %-3d  │ %d bytes  │ %s",
                        expectedSeq, p.data.length, progressBar(receivedSize, fileSize)));
                sendAck(socket, serverAddr, serverPort, expectedSeq);
                expectedSeq = 1 - expectedSeq; // Toggle bit: 0→1, 1→0
            } else {
                // Duplicate (server retransmitting because ACK was lost) — re-ACK politely
                stats[1]++;
                log(Packet.CLR_YELLOW, "SW-DUP", "Seq " + p.seqno + " is a duplicate → re-ACK");
                sendAck(socket, serverAddr, serverPort, p.seqno);
            }
        }
        System.out.println(Packet.CLR_GREEN + "[DONE] Stop-and-Wait transfer complete." + Packet.CLR_RESET);
    }

    /**
     * SELECTIVE REPEAT RECEIVER
     *
     * Behaviour:
     *  • Maintains a buffer for packets within the receive window.
     *  • Accepts AND buffers out-of-order packets (unlike GBN).
     *  • Sends an individual ACK for every received packet (in-order or not).
     *  • Delivers buffered data to file only in order, once gaps are filled.
     *
     * Sequence space = 2 * windowSize  (SR constraint to avoid ambiguity).
     */
    private static void receiveSelectiveRepeat(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                                FileOutputStream fos, int fileSize, int windowSize, int[] stats)
            throws IOException {

        int      seqCount    = 3 * windowSize; // Must match server seqCount; 3×window is safe for all protocols
        int      expectedSeq = 0;   // Next in-order slot we are waiting to deliver
        int      writtenSize = 0;

        // Per-slot receive state
        byte[][] recvBuffer  = new byte[seqCount][];
        boolean[] received   = new boolean[seqCount];

        System.out.println(Packet.CLR_CYAN + "[START] Selective Repeat receiver ready." + Packet.CLR_RESET);

        while (writtenSize < fileSize) {
            Packet p = receivePacket(socket);
            if (p == null) { stats[0]++; continue; }

            int seqNo = p.seqno;

            // Check if seqNo falls within our receive window [expectedSeq .. expectedSeq+wSize-1]
            if (isInWindow(seqNo, expectedSeq, windowSize, seqCount)) {
                if (!received[seqNo]) {
                    // Fresh packet — buffer it and mark slot
                    received[seqNo]   = true;
                    recvBuffer[seqNo] = p.data.clone();
                    log(Packet.CLR_GREEN, "SR-RECV",
                            String.format("Seq %-3d buffered  │ %d bytes", seqNo, p.data.length));
                } else {
                    log(Packet.CLR_YELLOW, "SR-DUP", "Seq " + seqNo + " already buffered");
                }
                // Individual ACK for every accepted packet (in or out of order)
                sendAck(socket, serverAddr, serverPort, seqNo);

                // Drain consecutive slots from expectedSeq onwards and write them in order
                while (received[expectedSeq]) {
                    fos.write(recvBuffer[expectedSeq]);
                    writtenSize += recvBuffer[expectedSeq].length;
                    received[expectedSeq]   = false;
                    recvBuffer[expectedSeq] = null;
                    log(Packet.CLR_BLUE, "SR-DELIVER",
                            String.format("Seq %-3d written   │ %s", expectedSeq, progressBar(writtenSize, fileSize)));
                    expectedSeq = (expectedSeq + 1) % seqCount;
                }
            } else {
                // Outside window — could be an old retransmit; ignore (server will re-send if needed)
                stats[1]++;
                log(Packet.CLR_YELLOW, "SR-OUT-WIN", "Seq " + seqNo + " outside window [" + expectedSeq
                        + ".." + (expectedSeq + windowSize - 1) % seqCount + "] → dropped");
            }
        }
        System.out.println(Packet.CLR_GREEN + "[DONE] Selective Repeat transfer complete." + Packet.CLR_RESET);
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    /**
     * Receives one DatagramPacket and deserialises it into a Packet object.
     * Returns null if the packet fails the CRC32 integrity check (i.e. is corrupt).
     */
    private static Packet receivePacket(DatagramSocket socket) throws IOException {
        byte[]         buf = new byte[Packet.HEADER_SIZE + Packet.DATA_SIZE + 16];
        DatagramPacket dp  = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        Packet p = new Packet(buf, dp.getLength());
        if (!p.isNotCorrupt()) {
            log(Packet.CLR_RED, "CORRUPT",
                    String.format("Seq %-3d CRC mismatch — dropped", p.seqno));
            return null;
        }
        return p;
    }

    /** Sends a raw ACK packet back to the server. */
    private static void sendAck(DatagramSocket socket, InetAddress addr, int port, int ackNo)
            throws IOException {
        byte[] ack = Packet.createAck(ackNo);
        socket.send(new DatagramPacket(ack, ack.length, addr, port));
        log(Packet.CLR_BLUE, "ACK-SENT", "ACK " + ackNo);
    }

    /**
     * Checks whether seqNo lies inside the receive window starting at base.
     * Works correctly across sequence-number wrap-around boundaries.
     */
    private static boolean isInWindow(int seqNo, int base, int wSize, int seqCount) {
        for (int i = 0; i < wSize; i++) {
            if ((base + i) % seqCount == seqNo) return true;
        }
        return false;
    }

    /**
     * Builds a compact ASCII progress bar, e.g.:  [████████░░░░░░░░░░░░]  40%
     *
     * @param received  Bytes received so far
     * @param total     Total expected bytes
     * @return Progress bar string (always 30 chars wide)
     */
    private static String progressBar(int received, int total) {
        int    pct   = Math.min(100, (int) ((received * 100L) / Math.max(total, 1)));
        int    filled = pct / 5;  // Each block = 5%
        String bar   = "█".repeat(filled) + "░".repeat(20 - filled);
        return String.format("[%s] %3d%%  (%d/%d B)", bar, pct, received, total);
    }

    /** Prints a coloured, labelled log line to stdout. */
    private static void log(String color, String label, String msg) {
        System.out.printf("%s[%-12s]%s %s%n", color, label, Packet.CLR_RESET, msg);
    }

    // ── Display Helpers ──────────────────────────────────────────────────────

    private static void printBanner(String ip, int sPort, int cPort, String file,
                                     String protocol, int window) {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║     Reliable UDP File Client  —  Java        ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Server     :  %-30s║%n", ip + ":" + sPort);
        System.out.printf( "║  Client Port:  %-30s║%n", cPort);
        System.out.printf( "║  Protocol   :  %-30s║%n", protocol.toUpperCase());
        System.out.printf( "║  Window     :  %-30s║%n", window + " packets");
        System.out.printf( "║  Target File:  %-30s║%n", file);
        System.out.println("╚══════════════════════════════════════════════╝\n");
    }

    private static void printStats(String proto, long fileSize, long elapsed, double throughput,
                                    int corrupt, int discarded, String savedAs) {
        System.out.println("\n╔══════════════ TRANSFER STATS ══════════════╗");
        System.out.printf( "║  Protocol       : %-27s║%n", proto.toUpperCase());
        System.out.printf( "║  File Size      : %-27s║%n", fileSize + " bytes");
        System.out.printf( "║  Duration       : %-27s║%n", elapsed + " ms");
        System.out.printf( "║  Throughput     : %-27s║%n", String.format("%.2f KB/s", throughput));
        System.out.printf( "║  Corrupt Dropped: %-27s║%n", corrupt);
        System.out.printf( "║  Out-of-Order   : %-27s║%n", discarded);
        System.out.printf( "║  Saved As       : %-27s║%n", "output/" + savedAs);
        System.out.println("╚════════════════════════════════════════════╝\n");
    }
}
