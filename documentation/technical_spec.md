# Technical Specification & Algorithm Deep-Dive

This document provides a detailed breakdown of the technology stack, architectural patterns, and networking algorithms implemented in the Reliable UDP Protocol project.

---

## Technology Stack

### 1. Core Language: Java (JDK 17+)
*   **Why?** Refactored from C to Java to ensure cross-platform compatibility without complex socket header management (`Winsock` vs `POSIX`).
*   **Features Used:** Object-Oriented Design, strongly-typed byte manipulation.

### 2. Networking: Java UDP API
*   **`DatagramSocket`:** Used for both the main listener (port 7777) and ephemeral worker sockets.
*   **`DatagramPacket`:** The carrier for our serialized custom `Packet` objects.

### 3. Concurrency: Java `java.util.concurrent`
*   **`ExecutorService` (CachedThreadPool):** The server uses a thread pool to handle multiple clients simultaneously. This prevents new connection requests from being blocked by ongoing file transfers.
*   **Thread Isolation:** Each client gets its own thread and a dedicated ephemeral UDP socket.

### 4. Integrity: `java.util.zip.CRC32`
*   **Algorithm:** 32-bit Cyclic Redundancy Check.
*   **Purpose:** Detects bit-flips or data corruption during wireless or noisy network transit. Packets that fail the check are discarded, triggering the reliability logic.

---

## Algorithms & Protocols

### 1. Handshake & Auto-Negotiation
Before the data flows, a **2-way handshake** occurs:
1.  Client sends a `Packet` containing the requested filename.
2.  Server parses the request, checks for file existence, and replies with a handshake string: `fileSize:protocol`.
3.  **Auto-Negotiation:** The client parses this string and automatically configures its receiver logic (GBN, SR, or SW) to match the server's current mode.

### 2. Stop-and-Wait (SW)
*   **Logic:** The simplest form of flow control.
*   **Sequence Numbers:** Uses "Alternating Bit" (0 or 1).
*   **Wait Mechanism:** Sender sends 1 packet and blocks entirely until an ACK with the matching sequence number returns.
*   **Recovery:** If the timer expires before an ACK arrives, the sender retransmits the same packet.

### 3. Go-Back-N (GBN)
*   **Logic:** Uses a **Sliding Window** to allow multiple packets in flight.
*   **Acknowlegement:** Uses **Cumulative ACKs**. If the receiver sends `ACK 5`, it implies it safely received 0, 1, 2, 3, 4, AND 5.
*   **Retransmission:** On timeout, the sender "goes back" to the last unACKed packet and retransmits the **entire window** starting from that point.
*   **Sequence Space:** Sequence numbers are bound by `3 × WindowSize` to prevent wrap-around ambiguity.

### 4. Selective Repeat (SR)
*   **Logic:** More efficient than GBN under high loss.
*   **Acknowlegement:** Uses **Individual ACKs**. Every packet is acknowledged independently.
*   **Retransmission:** On timeout, the sender retransmits **only** the specific packets that timed out.
*   **Buffering:** The receiver maintains a buffer to store out-of-order packets (e.g., if packet 2 is lost but 3 arrives, 3 is saved in a buffer until 2 is re-received).

### 5. Packet Loss Simulation (Seeded RNG)
*   **Algorithm:** Uses a `java.util.Random` initialized with a fixed seed from `server.in`.
*   **Logic:** For every packet dispatched, `rng.nextDouble()` is compared against the `plp` (Packet Loss Probability). If the random value is lower than `plp`, the packet is not sent (simulated drop).
*   **Reproducibility:** The fixed seed ensures that if you encounter a bug during a "lossy" run, you can re-run the exact same drop pattern to debug it.

---

## Data Serialization (The `Packet` Class)
Since UDP only sends raw bytes, we implemented a custom serialization engine:
1.  **Header (Header-to-Bytes):** Converts `seqno` (int) and `checksum` (long) into a fixed-length byte prefix.
2.  **Payload:** The actual file chunk (up to 512 bytes).
3.  **Deserialization:** The receiver slices the first few bytes back into integers and longs to validate the packet before processing the data.
