package com.neurovault.backend.transport;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.exception.ResourceNotFoundException;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.storage.exception.ContainerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.UUID;

/**
 * HTTP implementation of {@link ChunkTransport} streaming chunks to and from remote host nodes.
 */
@Component("httpChunkTransport")
public class HttpChunkTransport implements ChunkTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpChunkTransport.class);

    private final HostRepository hostRepository;

    public HttpChunkTransport(HostRepository hostRepository) {
        this.hostRepository = hostRepository;
    }

    @Override
    public InputStream openReadStream(UUID hostId, UUID chunkId, String capabilityToken) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found: " + hostId));

        String ip = host.getPublicIp() != null ? host.getPublicIp() : "127.0.0.1";
        String targetUrl = String.format("http://%s:8080/api/storage/chunks/%s", ip, chunkId);

        try {
            URL url = URI.create(targetUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            if (capabilityToken != null) {
                conn.setRequestProperty("X-Capability-Token", capabilityToken);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new ContainerException("HTTP read chunk failed with code " + responseCode + " from " + targetUrl);
            }

            return conn.getInputStream();
        } catch (IOException e) {
            throw new ContainerException("Failed to open HTTP read stream to host " + hostId + " at " + targetUrl, e);
        }
    }

    @Override
    public void streamToTarget(UUID hostId, UUID chunkId, UUID ownerId, InputStream sourceStream,
                               long size, String expectedSha256, String capabilityToken) {
        Host host = hostRepository.findById(hostId)
                .orElseThrow(() -> new ResourceNotFoundException("Host not found: " + hostId));

        String ip = host.getPublicIp() != null ? host.getPublicIp() : "127.0.0.1";
        String targetUrl = String.format("http://%s:8080/api/storage/chunks", ip);

        try {
            URL url = URI.create(targetUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            if (capabilityToken != null) {
                conn.setRequestProperty("X-Capability-Token", capabilityToken);
            }

            try (OutputStream os = conn.getOutputStream()) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = sourceStream.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_CREATED) {
                throw new ContainerException("HTTP write chunk failed with status " + responseCode + " to " + targetUrl);
            }
        } catch (IOException e) {
            throw new ContainerException("Failed to stream chunk to remote host " + hostId + " via HTTP", e);
        }
    }

    @Override
    public boolean verifyChunkIntegrity(UUID hostId, UUID chunkId, String expectedSha256) {
        try (InputStream in = openReadStream(hostId, chunkId, null)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expectedSha256);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getTransportType() {
        return "HTTP";
    }
}
