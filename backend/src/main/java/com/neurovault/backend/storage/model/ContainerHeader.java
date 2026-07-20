package com.neurovault.backend.storage.model;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Binary header stored at offset 0 of the {@code storage.container} file.
 * Fixed size: {@value #HEADER_SIZE} bytes.
 *
 * <h3>Binary Layout (256 bytes)</h3>
 * <pre>
 * Offset  Size   Field
 * ------  -----  ----------------------
 * 0       4      Magic bytes ("NVLT")
 * 4       4      Version (int)
 * 8       8      Total container size (long)
 * 16      8      Used data size (long)
 * 24      4      Chunk count (int)
 * 28      8      Metadata region offset (long)
 * 36      8      Metadata region size (long)
 * 44      8      Data region offset (long)
 * 52      8      Created timestamp epoch millis (long)
 * 60      8      Last modified timestamp epoch millis (long)
 * 68      188    Reserved (zero-padded for future expansion)
 * </pre>
 */
public class ContainerHeader {

    /** Fixed header size in bytes. */
    public static final int HEADER_SIZE = 256;

    /** Magic bytes identifying a valid NeuroVault container: "NVLT" */
    public static final byte[] MAGIC_BYTES = new byte[]{'N', 'V', 'L', 'T'};

    /** Current container format version. */
    public static final int CURRENT_VERSION = 1;

    /** Default metadata region size: 1 MB. Grows if needed. */
    public static final long DEFAULT_METADATA_REGION_SIZE = 1024L * 1024;

    private int version;
    private long totalSize;
    private long usedSize;
    private int chunkCount;
    private long metadataRegionOffset;
    private long metadataRegionSize;
    private long dataRegionOffset;
    private Instant createdAt;
    private Instant lastModifiedAt;

    public ContainerHeader() {
        this.version = CURRENT_VERSION;
        this.createdAt = Instant.now();
        this.lastModifiedAt = Instant.now();
    }

    /**
     * Serializes this header into a 256-byte array for writing to disk.
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.put(MAGIC_BYTES);
        buffer.putInt(version);
        buffer.putLong(totalSize);
        buffer.putLong(usedSize);
        buffer.putInt(chunkCount);
        buffer.putLong(metadataRegionOffset);
        buffer.putLong(metadataRegionSize);
        buffer.putLong(dataRegionOffset);
        buffer.putLong(createdAt.toEpochMilli());
        buffer.putLong(lastModifiedAt.toEpochMilli());
        // Remaining bytes are zero-padded by default
        return buffer.array();
    }

    /**
     * Deserializes a 256-byte array into a ContainerHeader.
     *
     * @param data exactly {@value #HEADER_SIZE} bytes
     * @return the parsed header
     * @throws IllegalArgumentException if magic bytes don't match
     */
    public static ContainerHeader fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Header data must be at least " + HEADER_SIZE + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Validate magic bytes
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (magic[0] != MAGIC_BYTES[0] || magic[1] != MAGIC_BYTES[1]
                || magic[2] != MAGIC_BYTES[2] || magic[3] != MAGIC_BYTES[3]) {
            throw new IllegalArgumentException("Invalid container file: magic bytes mismatch");
        }

        ContainerHeader header = new ContainerHeader();
        header.version = buffer.getInt();
        header.totalSize = buffer.getLong();
        header.usedSize = buffer.getLong();
        header.chunkCount = buffer.getInt();
        header.metadataRegionOffset = buffer.getLong();
        header.metadataRegionSize = buffer.getLong();
        header.dataRegionOffset = buffer.getLong();
        header.createdAt = Instant.ofEpochMilli(buffer.getLong());
        header.lastModifiedAt = Instant.ofEpochMilli(buffer.getLong());

        return header;
    }

    // ---- Getters and Setters ----

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getUsedSize() {
        return usedSize;
    }

    public void setUsedSize(long usedSize) {
        this.usedSize = usedSize;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public long getMetadataRegionOffset() {
        return metadataRegionOffset;
    }

    public void setMetadataRegionOffset(long metadataRegionOffset) {
        this.metadataRegionOffset = metadataRegionOffset;
    }

    public long getMetadataRegionSize() {
        return metadataRegionSize;
    }

    public void setMetadataRegionSize(long metadataRegionSize) {
        this.metadataRegionSize = metadataRegionSize;
    }

    public long getDataRegionOffset() {
        return dataRegionOffset;
    }

    public void setDataRegionOffset(long dataRegionOffset) {
        this.dataRegionOffset = dataRegionOffset;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }
}
