import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server — Reliable UDP File Server supporting three windowing protocols:
 *
 *   • Stop-and-Wait (sw)   – Simplest; sends one packet, waits for ACK before next.
 *   • Go-Back-N     (gbn)  – Sliding window; on timeout retransmits the ENTIRE window.
 *   • Selective Repeat (sr)– Sliding window; on timeout retransmits ONLY missing packets.
 *
 * Usage:
 *   java -cp bin Server              → defaults to GBN protocol
 *   java -cp bin Server gbn          → Go-Back-N
 *   java -cp bin Server sw           → Stop-and-Wait
 *   java -cp bin Server sr           → Selective Repeat
 *
 * Config: support/server.in  (see comments inside that file for parameter reference)
 *
 * Multi-client: Each incoming connection is handled by a dedicated thread.
 * The main listening socket stays free to accept new clients immediately.
 */
public class Server {

    // Milliseconds to wait for an incoming ACK before triggering a retransmit
    private static final int TIMEOUT_MS = 500;

    // ── Entry Point ──────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        // 1. Parse optional protocol argument (default = "gbn")
        String protocol = (args.length > 0) ? args[0].toLowerCase().trim() : "gbn";
        if (!protocol.equals("gbn") && !protocol.equals("sw") && !protocol.equals("sr")) {
            System.err.println("[ERROR] Unknown protocol '" + protocol + "'. Use: gbn | sw | sr");
            return;
        }

        // 2. Load configuration from support/server.in
        File configFile = new File("support/server.in");
        if (!configFile.exists()) {
            System.err.println("[ERROR] support/server.in not found.");
            return;
        }
        Scanner sc = new Scanner(configFile);
        int    serverPort = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        int    maxWindow  = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        int    seed       = Integer.parseInt(sc.nextLine().split("#")[0].trim());
        double plp        = Double.parseDouble(sc.nextLine().split("#")[0].trim());
        sc.close();

        printBanner(protocol, serverPort, maxWindow, plp);

        // 3. Thread pool — cached pool grows as needed for concurrent clients
        ExecutorService pool = Executors.newCachedThreadPool();

        // 4. Main socket — ONLY for accepting initial file-name requests
        DatagramSocket mainSock = new DatagramSocket(serverPort);
        byte[] buf = new byte[Packet.HEADER_SIZE + Packet.DATA_SIZE];

        // 5. Continuous accept loop
        while (true) {
            DatagramPacket incoming = new DatagramPacket(buf, buf.length);
            mainSock.receive(incoming);                 // Blocks until a client connects

            InetAddress clientAddr = incoming.getAddress();
            int         clientPort = incoming.getPort();

            // Parse the file-name request packet
            Packet req = new Packet(Arrays.copyOf(buf, incoming.getLength()), incoming.getLength());
            String fileName = new String(req.data).trim();

            System.out.println(Packet.CLR_CYAN + "\n[CONNECT] " + clientAddr + ":" + clientPort
                    + " → requested: " + fileName + Packet.CLR_RESET);

            // Hand off to a dedicated thread (non-blocking for the main loop)
            final String fn = fileName; final int s = seed; final int w = maxWindow;
            final double p = plp;       final String proto = protocol;

            pool.submit(() -> {
                try {
                    serveClient(clientAddr, clientPort, fn, w, new Random(s), p, proto);
                } catch (Exception e) {
                    System.err.println("[ERROR] Client handler failed: " + e.getMessage());
                }
            });
        }
    }

    // ── Client Handler ───────────────────────────────────────────────────────

    /**
     * Handles the full lifecycle of one client connection on a dedicated thread.
     * Creates its own DatagramSocket (ephemeral port) so the main socket stays free.
     *
     * @param addr       Client IP address
     * @param port       Client port number
     * @param fileName   Path to the file requested by the client
     * @param windowSize Sliding window capacity (not used for Stop-and-Wait)
     * @param rnd        Seeded RNG for deterministic packet-loss simulation
     * @param plp        Packet Loss Probability  (0.0 = no loss, 0.99 = max loss)
     * @param protocol   "gbn" | "sw" | "sr"
     */
    private static void serveClient(InetAddress addr, int port, String fileName,
                                    int windowSize, Random rnd, double plp, String protocol)
            throws IOException {

        // Dedicated socket — OS assigns a random available port automatically
        DatagramSocket sock = new DatagramSocket();
        sock.setSoTimeout(TIMEOUT_MS);

        // Validate the requested file exists
        File file = new File(fileName);
        if (!file.exists()) {
            System.err.println(Packet.CLR_RED + "[ERROR] File not found: " + fileName + Packet.CLR_RESET);
            sock.close();
            return;
        }

        long fileSize = file.length();

        // ── Handshake (2-way) ────────────────────────────────────────────
        // Step 1: send file size AND active protocol so the client auto-negotiates
        String handshake = fileSize + ":" + protocol;
        Packet handshakePkt = new Packet(0, handshake.getBytes(), handshake.length());
        byte[] hBytes = handshakePkt.toBytes();
        sock.send(new DatagramPacket(hBytes, hBytes.length, addr, port));

        // Step 2: wait for client's "ready" acknowledgement before sending data
        byte[] readyBuf = new byte[64];
        sock.receive(new DatagramPacket(readyBuf, readyBuf.length));

        System.out.println(Packet.CLR_GREEN + "[READY] " + protocol.toUpperCase()
                + " transfer started → " + fileName + " (" + fileSize + " bytes)" + Packet.CLR_RESET);

        // ── Transfer ─────────────────────────────────────────────────────
        // stats[0] = retransmissions,  stats[1] = simulated drops
        int[] stats = {0, 0};
        long startTime = System.currentTimeMillis();

        switch (protocol) {
            case "sw" -> stopAndWait(sock, addr, port, file, rnd, plp, stats);
            case "sr" -> selectiveRepeat(sock, addr, port, file, windowSize, rnd, plp, stats);
            default   -> goBackN(sock, addr, port, file, windowSize, rnd, plp, stats);
        }

        long   elapsed    = System.currentTimeMillis() - startTime;
        double throughput = (fileSize / 1024.0) / Math.max(elapsed / 1000.0, 0.001);
        int    totalPkts  = (int) Math.ceil((double) fileSize / Packet.DATA_SIZE);
        double lossRate   = totalPkts > 0 ? (stats[1] * 100.0) / (totalPkts + stats[0]) : 0;

        printStats(protocol, fileSize, elapsed, throughput, stats[0], stats[1], lossRate, totalPkts);
        sock.close();
    }

    // ── Protocol Implementations ─────────────────────────────────────────────

    /**
     * GO-BACK-N SENDER
     *
     * Behaviour:
     *  • Sends up to 'wSize' consecutive packets without waiting for ACKs.
     *  • ACKs are cumulative — receiving ACK N means all packets 0..N are confirmed.
     *  • On timeout, slides nextSeq back to 'base' and retransmits the ENTIRE window.
     *
     * @param stats int[]{retransmissions, simulatedDrops}
     */
    private static void goBackN(DatagramSocket sock, InetAddress addr, int port,
                                 File file, int wSize, Random rnd, double plp, int[] stats)
            throws IOException {

        long fileSize  = file.length();
        int  seqCount  = 3 * wSize;    // GBN sequence space must be ≥ 2*wSize; we use 3x for safety
        int  base      = 0;            // Oldest unacknowledged packet
        int  nextSeq   = 0;            // Next packet to be sent

        Packet[]        window = new Packet[seqCount];
        FileInputStream fis    = new FileInputStream(file);

        while (base * Packet.DATA_SIZE < fileSize) {

            // 1. Fill the window — keep sending until window full or EOF
            while (nextSeq < base + wSize && nextSeq * Packet.DATA_SIZE < fileSize) {
                byte[] data = readChunk(fis, (long) nextSeq * Packet.DATA_SIZE);
                Packet p    = new Packet(nextSeq % seqCount, data, data.length);
                window[nextSeq % seqCount] = p;

                if (rnd.nextDouble() > plp) {
                    dispatch(sock, p, addr, port);
                    log(Packet.CLR_GREEN, "GBN-SEND", String.format("Pkt %-3d │ %d bytes", p.seqno, data.length));
                } else {
                    stats[1]++;
                    log(Packet.CLR_RED, "GBN-DROP", String.format("Pkt %-3d │ simulated loss", nextSeq % seqCount));
                }
                nextSeq++;
            }

            // 2. Wait for a cumulative ACK
            try {
                byte[]         ackBuf = new byte[64];
                DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                sock.receive(ackPkt);

                int ackNo = Packet.parseAck(ackBuf, ackPkt.getLength());
                log(Packet.CLR_BLUE, "GBN-ACK ", String.format("ACK %-3d received", ackNo));

                // Slide the window forward by however many packets were freshly ACKed
                if (ackNo >= (base % seqCount)) {
                    base += (ackNo - (base % seqCount)) + 1;
                } else if ((base % seqCount) > seqCount / 2) {
                    // Handle seqno wrap-around
                    base += (seqCount - (base % seqCount)) + ackNo + 1;
                }

            } catch (SocketTimeoutException e) {
                // Timeout → retransmit ENTIRE window (core GBN behaviour)
                stats[0]++;
                log(Packet.CLR_YELLOW, "GBN-TIMEOUT",
                        String.format("Retransmit from base=%d  (total retransmits: %d)", base, stats[0]));
                nextSeq = base;  // Reset pointer forces the send-loop to re-send all window packets
            }
        }
        fis.close();
    }

    /**
     * STOP-AND-WAIT SENDER
     *
     * Behaviour:
     *  • Sends exactly 1 packet and waits for its specific ACK before proceeding.
     *  • On timeout or wrong ACK, retransmits the same packet.
     *  • Simplest but slowest — one packet in flight at a time.
     *  • Uses alternating bit sequence numbers (0, 1, 0, 1 …).
     *
     * @param stats int[]{retransmissions, simulatedDrops}
     */
    private static void stopAndWait(DatagramSocket sock, InetAddress addr, int port,
                                     File file, Random rnd, double plp, int[] stats)
            throws IOException {

        long            fileSize = file.length();
        int             base     = 0;
        FileInputStream fis      = new FileInputStream(file);

        while (base * Packet.DATA_SIZE < fileSize) {
            byte[] data = readChunk(fis, (long) base * Packet.DATA_SIZE);
            Packet p    = new Packet(base % 2, data, data.length); // Alternating bit (0 or 1)

            boolean acked = false;
            while (!acked) {
                // Send (or simulate a drop)
                if (rnd.nextDouble() > plp) {
                    dispatch(sock, p, addr, port);
                    log(Packet.CLR_GREEN, "SW-SEND", String.format("Pkt %-3d │ %d bytes", p.seqno, data.length));
                } else {
                    stats[1]++;
                    log(Packet.CLR_RED, "SW-DROP", String.format("Pkt %-3d │ simulated loss", p.seqno));
                }

                // Wait for matching ACK
                try {
                    byte[]         ackBuf = new byte[64];
                    DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                    sock.receive(ackPkt);
                    int ackNo = Packet.parseAck(ackBuf, ackPkt.getLength());

                    if (ackNo == p.seqno) {
                        log(Packet.CLR_BLUE, "SW-ACK ", String.format("ACK %-3d ✓", ackNo));
                        acked = true;
                    } else {
                        log(Packet.CLR_YELLOW, "SW-WRONG-ACK", "Expected " + p.seqno + " got " + ackNo);
                    }
                } catch (SocketTimeoutException e) {
                    stats[0]++;
                    log(Packet.CLR_YELLOW, "SW-TIMEOUT",
                            String.format("Retransmit Pkt %d  (total retransmits: %d)", p.seqno, stats[0]));
                }
            }
            base++;
        }
        fis.close();
    }

    /**
     * SELECTIVE REPEAT SENDER
     *
     * Behaviour:
     *  • Sends up to 'wSize' packets; each packet is individually acknowledged.
     *  • Window slides only when the BASE packet is ACKed.
     *  • On timeout, retransmits ONLY the packets that have NOT yet been ACKed.
     *  • Sequence space = 2 * wSize  (SR constraint to avoid aliasing).
     *
     * @param stats int[]{retransmissions, simulatedDrops}
     */
    private static void selectiveRepeat(DatagramSocket sock, InetAddress addr, int port,
                                         File file, int wSize, Random rnd, double plp, int[] stats)
            throws IOException {

        long     fileSize  = file.length();
        int      seqCount  = 3 * wSize;     // Must match GBN/client seqCount; 3×window is safe for all protocols
        int      base      = 0;             // First unACKed packet index
        int      nextSeq   = 0;             // Index of next packet to transmit

        boolean[]       acked  = new boolean[seqCount]; // Per-slot ACK status
        Packet[]        window = new Packet[seqCount];  // Buffered packets for retransmit

        FileInputStream fis = new FileInputStream(file);

        while (base * Packet.DATA_SIZE < fileSize) {

            // 1. Send un-sent packets within the current window
            while (nextSeq < base + wSize && nextSeq * Packet.DATA_SIZE < fileSize) {
                int slot = nextSeq % seqCount;
                if (window[slot] == null && !acked[slot]) {
                    byte[] data = readChunk(fis, (long) nextSeq * Packet.DATA_SIZE);
                    Packet p    = new Packet(slot, data, data.length);
                    window[slot] = p;

                    if (rnd.nextDouble() > plp) {
                        dispatch(sock, p, addr, port);
                        log(Packet.CLR_GREEN, "SR-SEND", String.format("Pkt %-3d │ %d bytes", slot, data.length));
                    } else {
                        stats[1]++;
                        log(Packet.CLR_RED, "SR-DROP", String.format("Pkt %-3d │ simulated loss", slot));
                    }
                }
                nextSeq++;
            }

            // 2. Await any individual ACK
            try {
                byte[]         ackBuf = new byte[64];
                DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                sock.receive(ackPkt);

                int ackNo = Packet.parseAck(ackBuf, ackPkt.getLength());
                log(Packet.CLR_BLUE, "SR-ACK ", String.format("ACK %-3d received", ackNo));

                // Mark slot ACKed and free its window buffer
                acked[ackNo]  = true;
                window[ackNo] = null;

                // Slide window forward past all consecutive ACKed slots
                while (base * Packet.DATA_SIZE < fileSize && acked[base % seqCount]) {
                    acked[base % seqCount] = false;
                    base++;
                }

            } catch (SocketTimeoutException e) {
                // Timeout → retransmit ONLY packets in window that are still unACKed
                stats[0]++;
                log(Packet.CLR_YELLOW, "SR-TIMEOUT",
                        String.format("Retransmitting unACKed pkts  (total retransmits: %d)", stats[0]));
                for (int i = base; i < nextSeq; i++) {
                    int slot = i % seqCount;
                    if (!acked[slot] && window[slot] != null) {
                        dispatch(sock, window[slot], addr, port);
                        log(Packet.CLR_YELLOW, "SR-RESEND", "Pkt " + slot);
                    }
                }
            }
        }
        fis.close();
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    /**
     * Reads up to DATA_SIZE bytes from the file starting at byteOffset.
     * Uses NIO channel.position() to avoid keeping the whole file in RAM.
     */
    private static byte[] readChunk(FileInputStream fis, long byteOffset) throws IOException {
        byte[] buf = new byte[Packet.DATA_SIZE];
        fis.getChannel().position(byteOffset);
        int read = fis.read(buf);
        return (read == Packet.DATA_SIZE) ? buf : Arrays.copyOf(buf, Math.max(read, 0));
    }

    /** Serialises the packet and wraps it in a DatagramPacket for sending. */
    private static void dispatch(DatagramSocket sock, Packet p, InetAddress addr, int port)
            throws IOException {
        byte[] raw = p.toBytes();
        sock.send(new DatagramPacket(raw, raw.length, addr, port));
    }

    /** Prints a coloured, labelled log line to stdout. */
    private static void log(String color, String label, String msg) {
        System.out.printf("%s[%-12s]%s %s%n", color, label, Packet.CLR_RESET, msg);
    }

    // ── Display Helpers ──────────────────────────────────────────────────────

    private static void printBanner(String protocol, int port, int window, double plp) {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║     Reliable UDP File Server  —  Java        ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Protocol   :  %-30s║%n", protocol.toUpperCase());
        System.out.printf( "║  Port       :  %-30s║%n", port);
        System.out.printf( "║  Window     :  %-30s║%n", window + " packets");
        System.out.printf( "║  Loss Sim   :  %-30s║%n", String.format("%.0f%%  packet drop probability", plp * 100));
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.println("║  Listening for client connections ...        ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");
    }

    private static void printStats(String proto, long fileSize, long elapsed, double throughput,
                                    int retransmits, int dropped, double lossRate, int totalPkts) {
        System.out.println("\n╔══════════════ TRANSFER STATS ══════════════╗");
        System.out.printf( "║  Protocol       : %-27s║%n", proto.toUpperCase());
        System.out.printf( "║  File Size      : %-27s║%n", fileSize + " bytes");
        System.out.printf( "║  Duration       : %-27s║%n", elapsed + " ms");
        System.out.printf( "║  Throughput     : %-27s║%n", String.format("%.2f KB/s", throughput));
        System.out.printf( "║  Packets Sent   : %-27s║%n", totalPkts);
        System.out.printf( "║  Retransmits    : %-27s║%n", retransmits);
        System.out.printf( "║  Pkts Dropped   : %-27s║%n", dropped);
        System.out.printf( "║  Loss Rate      : %-27s║%n", String.format("%.1f%%", lossRate));
        System.out.println("╚════════════════════════════════════════════╝\n");
    }
}
