package com.neurovault.backend.upload;

import com.neurovault.backend.encryption.ChunkStatus;
import com.neurovault.backend.encryption.FileEngineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory upload queue managing pending chunk upload tasks.
 *
 * <p>Tasks are organized by upload session ID. Each session has its own
 * FIFO queue of pending chunk uploads. Failed tasks can be re-enqueued
 * up to the configured maximum retry count.</p>
 *
 * <p>This component is session-scoped in memory; in a production deployment,
 * this could be backed by a persistent message queue.</p>
 */
@Component
@Slf4j
public class UploadQueue {

    private final Map<UUID, ConcurrentLinkedQueue<ChunkUploadTask>> sessionQueues = new ConcurrentHashMap<>();
    private final Map<UUID, List<ChunkUploadTask>> allTasks = new ConcurrentHashMap<>();
    private final int maxRetryCount;

    public UploadQueue(FileEngineConfig config) {
        this.maxRetryCount = config.getMaxRetryCount();
    }

    /**
     * Enqueues a chunk upload task for the given session.
     *
     * @param task the chunk upload task to enqueue
     */
    public void enqueue(ChunkUploadTask task) {
        sessionQueues
                .computeIfAbsent(task.getSessionId(), k -> new ConcurrentLinkedQueue<>())
                .offer(task);
        allTasks
                .computeIfAbsent(task.getSessionId(), k -> new ArrayList<>())
                .add(task);
        log.debug("Enqueued chunk {} for session {} (retry #{})",
                task.getChunkIndex(), task.getSessionId(), task.getRetryCount());
    }

    /**
     * Dequeues the next pending chunk upload task for a given session.
     *
     * @param sessionId the upload session ID
     * @return the next task, or {@code null} if the queue is empty
     */
    public ChunkUploadTask dequeue(UUID sessionId) {
        ConcurrentLinkedQueue<ChunkUploadTask> queue = sessionQueues.get(sessionId);
        if (queue == null) {
            return null;
        }
        return queue.poll();
    }

    /**
     * Returns the number of pending tasks for a session.
     *
     * @param sessionId the upload session ID
     * @return number of pending tasks
     */
    public int pendingCount(UUID sessionId) {
        ConcurrentLinkedQueue<ChunkUploadTask> queue = sessionQueues.get(sessionId);
        return queue != null ? queue.size() : 0;
    }

    /**
     * Re-enqueues a failed task for retry if the retry limit has not been exceeded.
     *
     * @param task the failed task to retry
     * @return {@code true} if the task was re-enqueued, {@code false} if retry limit exceeded
     */
    public boolean retryTask(ChunkUploadTask task) {
        if (task.getRetryCount() >= maxRetryCount) {
            task.setStatus(ChunkStatus.FAILED);
            log.warn("Chunk {} for session {} exceeded max retry count ({})",
                    task.getChunkIndex(), task.getSessionId(), maxRetryCount);
            return false;
        }
        task.markForRetry();
        sessionQueues
                .computeIfAbsent(task.getSessionId(), k -> new ConcurrentLinkedQueue<>())
                .offer(task);
        log.info("Re-enqueued chunk {} for session {} (retry #{})",
                task.getChunkIndex(), task.getSessionId(), task.getRetryCount());
        return true;
    }

    /**
     * Returns all tasks (in any status) for a given session.
     *
     * @param sessionId the upload session ID
     * @return list of all tasks, or empty list if none
     */
    public List<ChunkUploadTask> getAllTasks(UUID sessionId) {
        return allTasks.getOrDefault(sessionId, List.of());
    }

    /**
     * Counts completed (UPLOADED or VERIFIED) tasks for a session.
     *
     * @param sessionId the upload session ID
     * @return number of completed tasks
     */
    public int completedCount(UUID sessionId) {
        return (int) getAllTasks(sessionId).stream()
                .filter(t -> t.getStatus() == ChunkStatus.UPLOADED || t.getStatus() == ChunkStatus.VERIFIED)
                .count();
    }

    /**
     * Counts failed tasks for a session.
     *
     * @param sessionId the upload session ID
     * @return number of failed tasks
     */
    public int failedCount(UUID sessionId) {
        return (int) getAllTasks(sessionId).stream()
                .filter(t -> t.getStatus() == ChunkStatus.FAILED)
                .count();
    }

    /**
     * Removes all queues and task records for a session.
     *
     * @param sessionId the upload session ID to clean up
     */
    public void clearSession(UUID sessionId) {
        sessionQueues.remove(sessionId);
        allTasks.remove(sessionId);
        log.info("Cleared upload queue for session {}", sessionId);
    }
}
