import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Packet — serialization/deserialization engine for the Reliable UDP protocol.
 * Manages header encoding, CRC32 checksums, sequence numbers, and ACK helpers.
 *
 * Packet Layout (HEADER_SIZE = 8 bytes):
 *   [0-1] cksum  – CRC32-derived 16-bit checksum of the data payload
 *   [2-3] len    – total byte length (header + data)
 *   [4-7] seqno  – sequence number (data packets) or ACK number (ACK packets)
 *   [8..] data   – payload (up to DATA_SIZE bytes)
 */
public class Packet {

    // ── Constants ────────────────────────────────────────────────────────
    public static final int HEADER_SIZE = 8;    // Bytes reserved for the fixed header
    public static final int DATA_SIZE   = 500;  // Maximum payload size per packet (bytes)

    // ANSI terminal colour codes for coloured console output
    public static final String CLR_RESET  = "\u001B[0m";
    public static final String CLR_GREEN  = "\u001B[32m";
    public static final String CLR_RED    = "\u001B[31m";
    public static final String CLR_YELLOW = "\u001B[33m";
    public static final String CLR_BLUE   = "\u001B[34m";
    public static final String CLR_CYAN   = "\u001B[36m";

    // ── Fields ───────────────────────────────────────────────────────────
    public int    cksum;  // CRC32-derived 16-bit integrity check
    public int    len;    // Total packet length in bytes (header + data)
    public int    seqno;  // Sequence / acknowledgement number
    public byte[] data;   // Payload bytes (may be smaller than DATA_SIZE for the last packet)

    // ── Constructors ─────────────────────────────────────────────────────

    /**
     * Build a new data packet for transmission.
     *
     * @param seqno   Sequence number to stamp on this packet
     * @param data    Raw byte array source
     * @param dataLen Number of valid bytes to take from data[]
     */
    public Packet(int seqno, byte[] data, int dataLen) {
        this.seqno = seqno;
        this.data  = new byte[dataLen];
        System.arraycopy(data, 0, this.data, 0, dataLen);
        this.len   = HEADER_SIZE + dataLen;
        this.cksum = calculateChecksum();   // Checksum computed AFTER data is set
    }

    /**
     * Deserialise a received raw byte array into a Packet object.
     *
     * @param rawData Byte array received from the DatagramSocket
     * @param length  Number of bytes actually received (may be less than rawData.length)
     */
    public Packet(byte[] rawData, int length) {
        ByteBuffer buf = ByteBuffer.wrap(rawData, 0, length);

        // Unpack fixed header fields
        this.cksum = buf.getShort() & 0xFFFF; // & 0xFFFF prevents Java sign-extension bugs
        this.len   = buf.getShort() & 0xFFFF;
        this.seqno = buf.getInt();

        // Unpack variable-length payload
        int dataLen = this.len - HEADER_SIZE;
        if (dataLen > 0 && length >= this.len) {
            this.data = new byte[dataLen];
            buf.get(this.data);
        } else {
            this.data = new byte[0];
        }
    }

    // ── Static Helpers ───────────────────────────────────────────────────

    /**
     * Creates a raw 8-byte ACK payload.  ACK packets carry no data — only a header.
     *
     * @param ackno The sequence number being acknowledged
     * @return Ready-to-send byte array
     */
    public static byte[] createAck(int ackno) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.putShort((short) 65535);       // Fixed dummy checksum for ACK-only packets
        buf.putShort((short) HEADER_SIZE); // len = header only (no data payload)
        buf.putInt(ackno);
        return buf.array();
    }

    /**
     * Parses the ACK number from a raw received ACK byte array.
     *
     * @param rawData Received bytes
     * @param length  Number of bytes received
     * @return ACK number, or -1 if the buffer is too short to parse
     */
    public static int parseAck(byte[] rawData, int length) {
        if (length < HEADER_SIZE) return -1;
        ByteBuffer buf = ByteBuffer.wrap(rawData, 0, length);
        buf.getShort();      // Skip cksum
        buf.getShort();      // Skip len
        return buf.getInt(); // Return ackno
    }

    // ── Instance Methods ─────────────────────────────────────────────────

    /**
     * Serialises this packet into a flat byte array ready for a DatagramPacket.
     *
     * @return Byte array of exactly len bytes (header + data)
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buf.putShort((short) cksum);
        buf.putShort((short) len);
        buf.putInt(seqno);
        buf.put(data);
        return buf.array();
    }

    /**
     * Computes a 16-bit CRC32-based checksum over the data payload.
     * Truncated to 16 bits so it fits in the cksum header field.
     *
     * @return 16-bit checksum value
     */
    public int calculateChecksum() {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) (crc.getValue() & 0xFFFF);
    }

    /**
     * Returns true if the packet arrived intact (stored checksum == freshly computed checksum).
     * Returns false if any bit in the payload was flipped during transit.
     */
    public boolean isNotCorrupt() {
        return this.cksum == calculateChecksum();
    }
}
