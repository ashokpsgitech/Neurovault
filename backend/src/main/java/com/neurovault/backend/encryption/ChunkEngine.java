package com.neurovault.backend.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Engine responsible for splitting encrypted files into fixed-size chunks
 * and merging chunks back into a single file.
 *
 * <p>Each chunk is annotated with metadata including a unique ID, positional index,
 * SHA-256 hash, and CRC32 checksum for integrity verification.</p>
 *
 * <p>Default chunk size is 4 MB (4,194,304 bytes).</p>
 */
@Service
@Slf4j
public class ChunkEngine {

    /** Default chunk size: 4 MB. */
    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    private final IntegrityService integrityService;
    private final int chunkSize;

    /**
     * Creates a ChunkEngine with the default 4 MB chunk size.
     *
     * @param integrityService service for computing hashes and checksums
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ChunkEngine(IntegrityService integrityService) {
        this(integrityService, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Creates a ChunkEngine with a custom chunk size (useful for testing).
     *
     * @param integrityService service for computing hashes and checksums
     * @param chunkSize        the chunk size in bytes
     */
    public ChunkEngine(IntegrityService integrityService, int chunkSize) {
        this.integrityService = integrityService;
        this.chunkSize = chunkSize;
    }

    /**
     * Splits an encrypted file into chunks, generating metadata for each chunk.
     *
     * @param encryptedFile input stream of the encrypted file data
     * @param fileSize      total size of the encrypted file in bytes
     * @param ownerId       the UUID of the file owner
     * @return ordered list of {@link ChunkData} objects with populated metadata
     * @throws IOException if an I/O error occurs during reading
     */
    public List<ChunkData> splitFile(InputStream encryptedFile, long fileSize, UUID ownerId)
            throws IOException {

        List<ChunkData> chunks = new ArrayList<>();
        byte[] buffer = new byte[chunkSize];
        int chunkIndex = 0;
        int bytesRead;

        while ((bytesRead = readFully(encryptedFile, buffer)) > 0) {
            // Copy only the actual bytes read (last chunk may be smaller)
            byte[] chunkBytes = new byte[bytesRead];
            System.arraycopy(buffer, 0, chunkBytes, 0, bytesRead);

            ChunkData chunk = ChunkData.builder()
                    .chunkId(UUID.randomUUID())
                    .chunkIndex(chunkIndex)
                    .chunkSize(bytesRead)
                    .sha256Hash(integrityService.computeSha256(chunkBytes))
                    .checksum(integrityService.computeChecksum(chunkBytes))
                    .ownerId(ownerId)
                    .data(chunkBytes)
                    .status(ChunkStatus.PENDING)
                    .build();

            chunks.add(chunk);
            chunkIndex++;
        }

        log.info("Split file ({} bytes) into {} chunks (chunk size={})",
                fileSize, chunks.size(), chunkSize);
        return chunks;
    }

    /**
     * Merges an ordered list of chunks back into a single output stream.
     *
     * <p>Chunks are sorted by their {@code chunkIndex} before merging.
     * Throws {@link IllegalArgumentException} if chunk indices are not contiguous.</p>
     *
     * @param orderedChunks list of chunks to merge (need not be pre-sorted)
     * @param output        the output stream to write merged data to
     * @throws IOException              if an I/O error occurs during writing
     * @throws IllegalArgumentException if chunk order verification fails
     */
    public void mergeChunks(List<ChunkData> orderedChunks, OutputStream output) throws IOException {
        if (orderedChunks == null || orderedChunks.isEmpty()) {
            throw new IllegalArgumentException("Cannot merge empty chunk list");
        }

        // Sort by index
        List<ChunkData> sorted = orderedChunks.stream()
                .sorted(Comparator.comparingInt(ChunkData::getChunkIndex))
                .toList();

        // Verify order
        if (!verifyChunkOrder(sorted)) {
            throw new IllegalArgumentException("Chunk indices are not contiguous");
        }

        for (ChunkData chunk : sorted) {
            output.write(chunk.getData());
        }
        output.flush();

        long totalSize = sorted.stream().mapToLong(ChunkData::getChunkSize).sum();
        log.info("Merged {} chunks into {} bytes", sorted.size(), totalSize);
    }

    /**
     * Convenience method that merges chunks and returns the result as a byte array.
     *
     * @param orderedChunks list of chunks to merge
     * @return the merged data as a byte array
     * @throws IOException if an I/O error occurs
     */
    public byte[] mergeChunksToBytes(List<ChunkData> orderedChunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mergeChunks(orderedChunks, out);
        return out.toByteArray();
    }

    /**
     * Calculates the total number of chunks for a given file size.
     *
     * @param fileSize the file size in bytes
     * @return the number of chunks needed
     */
    public int calculateTotalChunks(long fileSize) {
        if (fileSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) fileSize / chunkSize);
    }

    /**
     * Verifies that chunk indices form a contiguous sequence starting from 0.
     *
     * @param chunks the list of chunks to verify (should be sorted by index)
     * @return {@code true} if indices are 0, 1, 2, ..., n-1 with no gaps or duplicates
     */
    public boolean verifyChunkOrder(List<ChunkData> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }

        List<ChunkData> sorted = chunks.stream()
                .sorted(Comparator.comparingInt(ChunkData::getChunkIndex))
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getChunkIndex() != i) {
                log.warn("Chunk order verification failed at position {}: expected index {}, got {}",
                        i, i, sorted.get(i).getChunkIndex());
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the configured chunk size in bytes.
     *
     * @return chunk size in bytes
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Reads exactly {@code buffer.length} bytes from the input stream,
     * or fewer only at the end of the stream.
     *
     * @return the number of bytes actually read, or 0 if at end of stream
     */
    private int readFully(InputStream in, byte[] buffer) throws IOException {
        int totalRead = 0;
        int remaining = buffer.length;

        while (remaining > 0) {
            int bytesRead = in.read(buffer, totalRead, remaining);
            if (bytesRead == -1) {
                break;
            }
            totalRead += bytesRead;
            remaining -= bytesRead;
        }
        return totalRead;
    }
}
