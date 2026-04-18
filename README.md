# Reliable UDP Protocol – Network Communication

[![Java](https://img.shields.io/badge/Language-Java-orange?style=flat-square&logo=java)](https://www.java.com)
[![Protocol](https://img.shields.io/badge/Transport-UDP-blue?style=flat-square)](https://en.wikipedia.org/wiki/User_Datagram_Protocol)
[![Protocols](https://img.shields.io/badge/Schemes-GBN%20%7C%20SR%20%7C%20SW-green?style=flat-square)]()

The most famous reliable transport layer protocol is TCP, but it is tightly integrated into the OS kernel. This project simulates an independent **Application-Layer Reliability Engine built on top of UDP**, implementing three classic windowing protocols from scratch.

The system guarantees reliable, ordered, error-checked file delivery over an intentionally unreliable network channel — complete with **live packet loss simulation**, **CRC32 corruption detection**, **automatic retransmission**, and **real-time performance metrics**.

---

## Architecture Overview

Three focused modules, each with a single responsibility:

| Module | File | Role |
|---|---|---|
| **Packet** | `src/Packet.java` | Serialises/deserialises datagrams; computes & validates CRC32 checksums |
| **Server** | `src/Server.java` | Multi-client sender; runs GBN / SR / SW over `DatagramSocket` with simulated loss |
| **Client** | `src/Client.java` | Receiver; live progress bar, per-transfer stats, protocol-matched ACK logic |

---

## Implemented Protocols

### 1. Go-Back-N (GBN)
- Sender keeps a sliding window of `N` unacknowledged packets in flight.
- ACKs are **cumulative** — ACK `n` implicitly confirms all packets `0 … n`.
- On timeout, the sender goes back and retransmits the **entire window** from the oldest unACKed packet.
- Receiver discards all out-of-order packets and re-ACKs the last good packet.

### 2. Selective Repeat (SR)
- Same sliding window, but ACKs are **individual** per packet.
- On timeout, only the **specific dropped/lost packets** are retransmitted — not the whole window.
- Receiver maintains a buffer to store and reorder out-of-order arrivals before writing to disk.
- Requires sequence space ≥ `2 × window size` to avoid aliasing.

### 3. Stop-and-Wait (SW)
- Simplest scheme: sends exactly **one packet**, then blocks waiting for its ACK.
- Uses alternating bit sequence numbers `(0, 1, 0, 1 …)`.
- On timeout or wrong ACK, the same packet is retransmitted.
- Lowest throughput but simplest to reason about.

---

## Core Mechanics

### CRC32 Checksum Integrity
Every data packet carries a 16-bit CRC32-derived checksum in its header. On arrival, the receiver recomputes the checksum over the payload and compares. A mismatch means the packet was corrupted in transit — it is silently discarded, forcing the sender to time out and retransmit.

### Packet Loss Simulation
The server uses a seeded RNG to randomly drop outgoing packets with probability `plp` (set in `server.in`). This exercises the retransmission and recovery paths of all three protocols without needing a real lossy network.

### Multi-Client Threading
Each incoming file request is handed off to a worker thread (`ExecutorService.CachedThreadPool`). The main listening socket immediately returns to accept the next client. Each worker thread creates its own ephemeral `DatagramSocket`, so multiple file transfers run fully in parallel without interfering.

### Transfer Statistics
After every completed transfer, both Server and Client print a formatted stats block:
```
╔══════════════ TRANSFER STATS ══════════════╗
║  Protocol       : GBN                      ║
║  File Size      : 6007 bytes               ║
║  Duration       : 134 ms                   ║
║  Throughput     : 43.82 KB/s               ║
║  Retransmits    : 2                         ║
║  Pkts Dropped   : 1                         ║
║  Loss Rate      : 7.1%                     ║
╚════════════════════════════════════════════╝
```

### Live Progress Bar
The client prints a live progress bar as it receives chunks:
```
[RECV        ] Seq 7  │ 500 bytes │ [████████████░░░░░░░░] 60%  (3604/6007 B)
```

---

## Project Structure

```text
📦 Reliable-UDP-Protocol-Network-Communication
 ┣ 📂 bin                   ← Compiled .class files (auto-generated, do not edit)
 ┣ 📂 input                 ← Files the server can serve to clients
 ┃ ┗ 📜 server.txt
 ┣ 📂 output                ← Received files are saved here by the client
 ┣ 📂 src                   ← All Java source code
 ┃ ┣ 📜 Client.java         ← Receiver: progress bar, 3 protocol modes, stats
 ┃ ┣ 📜 Packet.java         ← Serialisation engine + CRC32 checksum helpers
 ┃ ┗ 📜 Server.java         ← Sender:  threading, 3 protocol modes, loss sim, stats
 ┣ 📂 support               ← Configuration files
 ┃ ┣ 📜 client.in           ← Client settings  (IP, ports, file, window size)
 ┃ ┗ 📜 server.in           ← Server settings  (port, window, seed, loss %)
 ┗ 📜 README.md
```

---

## Complete Quickstart Guide

### Step 1 — Compile
Run this once from the project root directory. Output `.class` files go into `bin/`.
```bash
javac -d bin src/*.java
```

### Step 2 — Start the Server (Terminal 1)
Choose your protocol with the optional argument. Defaults to **GBN** if omitted.
```bash
# Go-Back-N (default)
java -cp bin Server gbn

# Stop-and-Wait
java -cp bin Server sw

# Selective Repeat
java -cp bin Server sr
```

### Step 3 — Start the Client (Terminal 2)
The client reads defaults from `support/client.in`. You can override the target file and protocol via command-line arguments.
```bash
# Use all defaults from client.in (GBN + file from config)
java -cp bin Client

# Override the requested file
java -cp bin Client input/server.txt

# Override file AND protocol (must match what the server is running!)
java -cp bin Client input/server.txt gbn
java -cp bin Client input/server.txt sw
java -cp bin Client input/server.txt sr
```

> ⚠️ **Important:** The protocol specified for the Client must match the one the Server is running. Mismatched protocols will cause incorrect ACK/retransmit behaviour.

---

## Configuration Customization

Both config files live in `support/` and support inline `#` comments.

### `support/server.in`
```
7777    # Line 1: Port the server listens on for incoming connections
5       # Line 2: Max Window Size — how many unACKed packets can be in-flight at once
7       # Line 3: Random Seed — makes packet-loss simulation reproducible
0.0     # Line 4: Packet Loss Probability (0.0 = no loss, 0.99 = ~99% loss)
```

| Line | Parameter | Description |
|---|---|---|
| 1 | **Server Port** | The local port that the server opens and listens on |
| 2 | **Max Window Size** | Go-Back-N / SR window capacity (number of unACKed packets allowed in flight) |
| 3 | **Random Seed** | Fixed seed makes the drop pattern identical every run — great for debugging |
| 4 | **Packet Loss Probability** | Float `0.0–0.99`. Set to `0.3` to simulate a 30% lossy network |

### `support/client.in`
```
127.0.0.1           # Line 1: Server IP address (127.0.0.1 = localhost)
7777                # Line 2: Server port — must match server.in Line 1
9999                # Line 3: Client's own local port for receiving data
input/server.txt    # Line 4: Path to the file the client requests from the server
5                   # Line 5: Client window size — must match server.in Line 2
```

| Line | Parameter | Description |
|---|---|---|
| 1 | **Server IP** | IP of the machine running the server |
| 2 | **Server Port** | Must match `server.in` Line 1 |
| 3 | **Client Port** | Local port the client binds to for receiving incoming data chunks |
| 4 | **Target File** | Relative path to the file on the server machine to be transferred |
| 5 | **Window Size** | Should match the server's window size for correct protocol behaviour |

---

## Features

- [x] Go-Back-N (GBN) — sliding window with cumulative ACKs
- [x] Selective Repeat (SR) — sliding window with individual ACKs and receive-side buffering
- [x] Stop-and-Wait (SW) — alternating-bit protocol
- [x] CRC32 Packet Integrity Verification
- [x] Configurable Packet Loss Simulation (per run)
- [x] Multi-Client Concurrent Sessions (ThreadPool)
- [x] Live ASCII Progress Bar
- [x] Per-Transfer Statistics (throughput, retransmits, loss rate, duration)
- [x] Command-Line Protocol & File Selection
- [x] ANSI Colour-Coded Console Logs
- [x] Supports any file type (text, HTML, binary)
- [x] Inline config comments via `#` syntax

---

## Author

- [Shambhoolal Narwaria](https://github.com/mr-narwaria)
