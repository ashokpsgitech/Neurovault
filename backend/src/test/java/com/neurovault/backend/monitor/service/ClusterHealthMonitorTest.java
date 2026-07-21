package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.monitor.model.HostHealthInfo;
import com.neurovault.backend.monitor.model.HostHealthStatus;
import com.neurovault.backend.replication.dto.ClusterHealthResponse;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_monitor;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class ClusterHealthMonitorTest {

    @Autowired
    private ClusterHealthMonitor healthMonitor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private StorageContainerRepository containerRepository;

    @Autowired
    private HostHeartbeatRepository heartbeatRepository;

    private User testUser;

    @BeforeEach
    public void setup() {
        heartbeatRepository.deleteAll();
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("monitoruser")
                .email("mon@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);
    }

    @Test
    public void testEvaluateHostHealth_Healthy() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("HealthyHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(100L)
                .usedCapacityBytes(200L) // free = 700 bytes (70%)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/healthy")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        heartbeatRepository.save(HostHeartbeat.builder()
                .host(host)
                .cpuUsagePercent(15.0)
                .memoryUsagePercent(45.0)
                .storageUsedBytes(200L)
                .status(Host.Status.ONLINE)
                .build());

        HostHealthInfo info = healthMonitor.evaluateHostHealth(host);

        assertEquals(HostHealthStatus.HEALTHY, info.healthStatus());
        assertTrue(info.issues().isEmpty());
        assertEquals(15.0, info.cpuUsage());
        assertEquals(45.0, info.memoryUsage());
        assertEquals(700L, info.availableCapacity());
    }

    @Test
    public void testEvaluateHostHealth_HeartbeatTimeout() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("TimeoutHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(100L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now().minusMinutes(5)) // well past the 90 seconds timeout
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/timeout")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        HostHealthInfo info = healthMonitor.evaluateHostHealth(host);

        assertEquals(HostHealthStatus.UNREACHABLE, info.healthStatus(),
                "ONLINE host with timed out heartbeat should be UNREACHABLE");
        assertFalse(info.issues().isEmpty());
        assertTrue(info.issues().get(0).contains("heartbeat has timed out"));
    }

    @Test
    public void testEvaluateHostHealth_LowStorage() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("LowStorageHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(900L) // reserved 900
                .usedCapacityBytes(50L)     // used 50 -> available = 50 (5% of 1000)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/low")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        HostHealthInfo info = healthMonitor.evaluateHostHealth(host);

        assertEquals(HostHealthStatus.LOW_STORAGE, info.healthStatus());
        assertFalse(info.issues().isEmpty());
        assertTrue(info.issues().get(0).contains("Low storage"));
    }

    @Test
    public void testEvaluateHostHealth_ContainerCorrupted() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("CorruptHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(100L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/corrupt")
                .totalSize(1000L)
                .status(StorageContainer.Status.CORRUPTED)
                .build());

        HostHealthInfo info = healthMonitor.evaluateHostHealth(host);

        assertEquals(HostHealthStatus.CONTAINER_FAILURE, info.healthStatus());
        assertFalse(info.issues().isEmpty());
        assertTrue(info.issues().get(0).contains("CORRUPTED"));
    }

    @Test
    public void testGetOverallClusterHealth_StateCascade() {
        // No hosts -> CRITICAL
        ClusterHealthResponse emptyResponse = healthMonitor.getOverallClusterHealth();
        assertEquals(ClusterHealthResponse.HealthLevel.CRITICAL, emptyResponse.getHealthLevel());

        // 1. Create two healthy hosts
        Host host1 = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("ClusterHost1")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(100L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host1)
                .filePath("/tmp/cluster1")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        Host host2 = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("ClusterHost2")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(100L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host2)
                .filePath("/tmp/cluster2")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        ClusterHealthResponse healthyResponse = healthMonitor.getOverallClusterHealth();
        assertEquals(ClusterHealthResponse.HealthLevel.HEALTHY, healthyResponse.getHealthLevel());

        // Mark one Host offline -> DEGRADED (since under-replicated count is 0 and only 1/2 hosts are offline, not > 50%)
        host1.setStatus(Host.Status.OFFLINE);
        hostRepository.save(host1);

        ClusterHealthResponse degradedResponse = healthMonitor.getOverallClusterHealth();
        assertEquals(ClusterHealthResponse.HealthLevel.DEGRADED, degradedResponse.getHealthLevel());
    }
}
