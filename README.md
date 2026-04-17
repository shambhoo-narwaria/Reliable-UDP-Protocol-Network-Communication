# Reliable UDP Protocol – Network Communication (Java Refactor)

The most famous reliable transport layer protocol is TCP, but it dictates fixed control flow heavily integrated into the OS. This project simulates an independent reliable data transfer protocol by implementing a highly configurable **Application-Layer Reliability Engine on top of UDP**. 

This system guarantees reliable data transfer between a client and a server bypassing the unreliability of standard UDP. It employs a **Go-Back-N (GBN)** sliding window scheme and mathematical **CRC32 Checksums** to detect, recover, and reconstruct data that is arbitrarily dropped, out of order, or violently corrupted across a network stream.

**The codebase has been entirely refactored from C to Java** to eliminate Cross-Platform C/C++ networking complications, ensuring pure native compilation whether you are running on Windows `winsock2`, Linux POSIX `sys/socket.h`, or macOS!

---

## Architecture Overview

The system strictly decouples its concerns into three central modules:

- **Packet (`src/Packet.java`)**: The serialization engine. Converts app-data to binary arrays attached with sequence IDs, lengths, and `CRC32 Checksums` to identify byte-level mutations.
- **Server (`src/Server.java`)**: Anchors a `DatagramSocket`, listens for initialization query files, and controls the Sliding Window mechanism that regulates data outflow. Dynamically triggers synthetic *Packet Loss* based on `support/server.in` rules to demonstrate reliability recovery!
- **Client (`src/Client.java`)**: Reconstructs incoming split byte streams, rigorously validates sequential bounds, and dispatches Cumulative Acknowledgements (`ACKs`) back to the server to advance the stream.

---

## Core Mechanics

### 1. Verification (CRC32 Checksums)
Every packet contains an embedded Checksum metric attached to its header. If a bit flips due to unshielded network noise, the client mathematically identifies the anomaly upon arrival, drops the corrupt block silently, and forces the Server to time-out and re-transmit!

### 2. Timeouts & Retransmits
If a packet is swallowed by network congestion (or explicitly skipped by our `plp` Packet Loss Probability generator), the Client ignores anything sequentially out of bounds. The Server will eventually strike a `Timeout_ms` limit while waiting for the ACK, forcing a Window Slide back to the oldest unacknowledged `base`.

---

## Project Structure

This uses a standard Enterprise-tier separation linking Source definitions (`src`) and Machine Binaries (`bin`).

```text
📦 Reliable-UDP-Protocol-Network-Communication
 ┣ 📂 bin                 // Compiled executable classes automatically generated here.
 ┣ 📂 input               // Files hosted strictly to be requested by external clients.
 ┣ 📂 output              // Reassembled streams are securely saved here after verification.
 ┣ 📂 src
 ┃ ┣ 📜 Client.java       // Handles continuous receiving, ack-ing, and reconstructing.
 ┃ ┣ 📜 Packet.java       // Standardizes sequence serialization & Checksum hashes.
 ┃ ┗ 📜 Server.java       // Governs Go-Back-N (GBN) timeouts, delays, and transmissions.
 ┣ 📂 support
 ┃ ┣ 📜 client.in         // Defines IP, Ports, target File name, Window Size constraints.
 ┃ ┗ 📜 server.in         // Defines Port, Window bounds, Random Seed, Packet Loss Probability %.
 ┣ 📜 README.md           // Documentation.
```

---

## Complete Quickstart Guide

### 1. Compile the Source Code 
We separate source from build logic! Run this from the root directory to build `.class` binaries into the `bin/` execution folder.
```bash
mkdir bin     # If it doesn't already exist
javac -d bin src/*.java
```

### 2. Start the Server (Terminal 1)
Boot up the `Server` using the newly defined classpath target (`-cp bin`). It will anchor onto the configurations inside `support/server.in`.
```bash
java -cp bin Server
```

### 3. Start the Client (Terminal 2)
In a secondary terminal, initialize the `Client`. It will parse `support/client.in`, query the server, negotiate the `Metadata (File Sizes)`, and commence the downloading sequence!
```bash
java -cp bin Client
```

*Look closely at your terminals! You will clearly see color-coded simulated `[DROP]` actions triggered by the server, followed accurately by `[TIMEOUT]` logs, and the resulting `[OUT-ORDER]` rejection handled effectively by the Client logic before recovering.*

---

## Configuration Customization

Both client and server contain `.in` files inside the `support/` folder that act as tuning parameters. 
**These files natively support inline human-readable comments using the `#` symbol!**

### `support/server.in`
Controls the physical limitations and synthetic environments of the server.
* **Line 1 (Port)**: Local port number the server opens to listen for oncoming Client requests.
* **Line 2 (Max Window Size)**: In Go-Back-N, controls how many packets can be sent out eagerly before stopping and awaiting ACKs.
* **Line 3 (Random Seed)**: Network simulations use Random Number Generators (RNG). Defining a seed forces mathematical randomness to repeat exactly, aiding in debugging.
* **Line 4 (Packet Loss Probability)**: A float ranging from `0.0` to `0.99`. e.g., `0.3` = 30% chance an outgoing packet is explicitly swallowed.

### `support/client.in`
Controls target connectivity options mapping back to the server block logic.
* **Line 1 (Server IP)**: Defaults natively to `127.0.0.1` (localhost).
* **Line 2 (Server Port)**: The target listening port initialized in `server.in`.
* **Line 3 (Client Port)**: Secondary port bound locally by the client to asynchronously receive stream-data chunks.
* **Line 4 (Target File)**: Virtual path of the target file mapped natively onto the Server (e.g. `input/server.txt`).
* **Line 5 (Window Size)**: Synchronization limit representing the total byte-allowance window parallel to the Server configuration.

---

## Authors

- Refactored & Managed By Shambhoolal Narwaria ([@mr-narwaria](https://github.com/mr-narwaria))
