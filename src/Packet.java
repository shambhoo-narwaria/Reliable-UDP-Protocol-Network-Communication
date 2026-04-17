import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Packet class represents the structure of a single UDP datagram payload for this Reliable UDP protocol.
 * It manages serialization (converting object to byte array) and deserialization (byte array to object),
 * along with Sequence Numbers, Checksum calculations, and parsing Acknowledgements.
 */
public class Packet {
    // Header size in bytes: 2 bytes for cksum, 2 bytes for len, 4 bytes for seqno
    public static final int HEADER_SIZE = 8;
    // Maximum data payload size in bytes per packet
    public static final int DATA_SIZE = 500;
    
    // Packet fields
    public int cksum;   // Checksum to detect corruption
    public int len;     // Total length (Header + Data)
    public int seqno;   // Sequence number / Acknowledgement number
    public byte[] data; // Application data payload

    /**
     * Constructs a new Packet for sending data.
     * @param seqno The sequence number of the packet
     * @param data The raw data to include in the payload
     * @param dataLen The length of data to include
     */
    public Packet(int seqno, byte[] data, int dataLen) {
        this.seqno = seqno;
        this.data = new byte[dataLen];
        System.arraycopy(data, 0, this.data, 0, dataLen);
        this.len = HEADER_SIZE + dataLen;
        this.cksum = calculateChecksum();
    }

    /**
     * Constructs a Packet by deserializing a received byte array.
     * @param rawData The raw bytes received over the network
     * @param length The number of bytes received
     */
    public Packet(byte[] rawData, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(rawData, 0, length);
        
        // Unpack header
        this.cksum = buffer.getShort() & 0xFFFF; // & 0xFFFF prevents sign-extension issues
        this.len = buffer.getShort() & 0xFFFF;
        this.seqno = buffer.getInt();
        
        // Unpack payload data
        int dataLen = this.len - HEADER_SIZE;
        if (dataLen > 0 && length >= this.len) {
            this.data = new byte[dataLen];
            buffer.get(this.data);
        } else {
            this.data = new byte[0];
        }
    }

    /**
     * Creates an Acknowledgement (ACK) packet. ACK packets only contain a header (no data).
     * @param ackno The sequence number being acknowledged
     * @return A byte array ready to be sent over DatagramSocket
     */
    public static byte[] createAck(int ackno) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putShort((short) 65535);       // Dummy checksum strictly used for ACKs
        buffer.putShort((short) HEADER_SIZE); // ACK packets have no data payload
        buffer.putInt(ackno);
        return buffer.array();
    }

    /**
     * Parses an incoming byte array to extract an Acknowledgement number.
     * @param rawData Received ACK byte array
     * @param length Length of the received ACK byte array
     * @return The parsed ACK number, or -1 if the array is invalid
     */
    public static int parseAck(byte[] rawData, int length) {
        if (length < HEADER_SIZE) return -1;
        ByteBuffer buffer = ByteBuffer.wrap(rawData, 0, length);
        
        buffer.getShort();     // Skip checksum (not validated for ACKs in this demo)
        buffer.getShort();     // Skip length 
        return buffer.getInt(); // Return the parsed ACK sequence number
    }

    /**
     * Serializes this packet into a byte array for network transmission.
     * @return Byte array containing the header and data payload
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buffer.putShort((short) cksum);
        buffer.putShort((short) len);
        buffer.putInt(seqno);
        buffer.put(data);
        return buffer.array();
    }

    /**
     * Computes the checksum of the data payload using the CRC32 algorithm.
     * This ensures that packet corruption can be mathematically detected.
     * @return A 16-bit checksum represented as an integer
     */
    public int calculateChecksum() {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) (crc.getValue() & 0xFFFF);
    }
    
    /**
     * Validates if the packet was received intact by comparing its stated checksum
     * to the freshly calculated checksum of its data payload.
     * @return True if clean/uncorrupted, False if corrupted.
     */
    public boolean isNotCorrupt() {
        return this.cksum == calculateChecksum();
    }
}
