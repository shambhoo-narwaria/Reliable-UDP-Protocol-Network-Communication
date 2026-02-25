### Reliable UDP Protocol – Network Communication
The most famous reliable transport layer protocol is TCP. This project simulates a reliable data transfer protocol by implementing a reliable layer on UDP
The reliable layer is implemented as an application layer protocol, as implementing it as a transport layer protocol would require changing the OS code. It
demonstrates reliable data transfer between a client and a server using the Selective Repeat (SR), Go-Back-N (GBN), and Stop and Wait scheme and checks 
errors using checksum. These protocols ensure the reliable delivery of data over an unreliable network.

## Project Overview

The project consists of two main components:
- Server: The server component is responsible for sending data to the client and managing packet transmissions.
- Client: The client component is responsible for receiving data from the server and acknowledging received packets.

## Implementation Details

- The version of the Reliable Data Transfer Protocol
## rdt 1.0 - reliable channel
•	No errors 
•	no loss of packet  

## rdt 2.0 - unreliable channel
•	The lying channel may flip bit in the packet          
•	add a checksum to detect bit errors

## rdt 3.0 - unreliable channel
•	bit errors               
•	packet loss             

### Server Side
- The server code (`server.c`) implements the Selective Repeat (SR), Go-Back-N (GBN) and Stop and Wait protocols for reliable data transfer.
- It listens on a specific port for incoming client connections.
- It sends data packets to the connected client and handles acknowledgments.
- The server can handle packet losses, retransmissions, and out-of-order acknowledgments to ensure data reliability.

### Client Side
- The client code (`client.c`) also implements the Selective Repeat (SR), Go-Back-N (GBN) and Stop and Wait protocols for reliable data transfer
- It connects to the server and waits for data packets.
- The client acknowledges received packets and handles retransmissions when necessary.
- The client can process data packets out of order and ensure all data is written to the file in the correct order.

### How to Run

1. Compile the server and client code separately using a C compiler (e.g., GCC):

2. Run the server and client on their respective hosts:

3. Configuration file for the client and server in client.in and server.in respectetively. 

4. The server and client will establish a connection and perform reliable data transfer. The client will receive the data and save it to the
   specified output file.

### Configuration
- You can adjust parameters like the window size and timeouts in the code to optimize performance and reliability based on your network conditions.

### Error Handling
- Both the server and client include error handling to deal with potential issues, such as lost packets and corrupted data.

## Features
- [x] Reliable Data Transfer Protocol
- [x] Stop and Wait Method
- [x] Selective Repeat Method
- [x] GBN Method
- [x] Supporting file types (HTML, TXT, Images)
- [x] Error Detection Using Checksum
- [x] Congestion Control Handling
- [x] Loss Simulation to test real scenarios

## Project Structure

The project directory structure is as follows:
- `server.c`: Server code for reliable data transfer.
- `client.c`: Client code for reliable data reception.
- `Reliable.h`: Header file containing data structures and constants.
- `support/client.in`: Configuration file for the client.
- `support/server.in`: Configuration file for the server.
- `output/server.txt`: Sample output data file.
- `input/server.txt`: Sample input data file.


## Authors

- [Shambhoolal Narwaria](https://github.com/mr-narwaria)

Enjoy reliable data transfer using ReliableUDP project!
