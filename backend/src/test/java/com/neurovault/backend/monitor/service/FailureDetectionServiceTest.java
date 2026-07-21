package com.neurovault.backend.monitor.service;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.replication.event.ClusterEventType;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_failuredetect;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class FailureDetectionServiceTest {

    @Autowired
    private FailureDetectionService failureDetectionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private StorageContainerRepository containerRepository;

    private User testUser;

    @BeforeEach
    public void setup() {
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("failuser")
                .email("fail@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);
    }

    @Test
    public void testDetectFailures_HostOffline_And_Deduplication() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("FailHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(100L)
                .status(Host.Status.OFFLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/fail")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        // First run should detect the failure and publish an event
        int events = failureDetectionService.detectFailures();
        assertEquals(1, events);

        Map<UUID, Set<ClusterEventType>> alerts = failureDetectionService.getAlertedHosts();
        assertTrue(alerts.containsKey(host.getId()));
        assertTrue(alerts.get(host.getId()).contains(ClusterEventType.HOST_OFFLINE));

        // Second run should deduplicate and publish 0 events
        int repeatEvents = failureDetectionService.detectFailures();
        assertEquals(0, repeatEvents);
    }

    @Test
    public void testDetectFailures_DiskFull() {
        Host host = hostRepository.save(Host.builder()
                .owner(testUser)
                .name("DiskFullHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(500L)
                .usedCapacityBytes(500L) // Available capacity = 0 bytes
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build());

        containerRepository.save(StorageContainer.builder()
                .host(host)
                .filePath("/tmp/full")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build());

        int events = failureDetectionService.detectFailures();
        assertEquals(1, events);

        Map<UUID, Set<ClusterEventType>> alerts = failureDetectionService.getAlertedHosts();
        assertTrue(alerts.get(host.getId()).contains(ClusterEventType.DISK_FULL));
    }
}
