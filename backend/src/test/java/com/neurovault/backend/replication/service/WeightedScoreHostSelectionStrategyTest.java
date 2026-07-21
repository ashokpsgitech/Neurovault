package com.neurovault.backend.replication.service;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.UserRepository;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.HostHeartbeatRepository;
import com.neurovault.backend.repository.ChunkReplicaRepository;
import com.neurovault.backend.repository.StorageContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_selection;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class WeightedScoreHostSelectionStrategyTest {

    @Autowired
    private WeightedScoreHostSelectionStrategy strategy;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StorageContainerRepository containerRepository;

    private User testUser;

    @BeforeEach
    public void setup() {
        containerRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("testowner")
                .email("owner@example.com")
                .password("encoded_pass")
                .role(User.Role.HOST)
                .build();
        userRepository.save(testUser);
    }

    @Test
    public void testSelectHosts_FilteringOfflineAndUnhealthy() {
        // Create 1 ONLINE healthy host, 1 OFFLINE host
        Host onlineHost = Host.builder()
                .owner(testUser)
                .name("OnlineHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(100L)
                .usedCapacityBytes(100L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(onlineHost);

        StorageContainer container1 = StorageContainer.builder()
                .host(onlineHost)
                .filePath("/tmp/container1")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(container1);

        Host offlineHost = Host.builder()
                .owner(testUser)
                .name("OfflineHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(100L)
                .usedCapacityBytes(100L)
                .status(Host.Status.OFFLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(offlineHost);

        StorageContainer container2 = StorageContainer.builder()
                .host(offlineHost)
                .filePath("/tmp/container2")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(container2);

        // Select 2 hosts
        List<Host> selected = strategy.selectHosts(2, 50L, Set.of());
        assertEquals(1, selected.size());
        assertEquals("OnlineHost", selected.get(0).getName());
    }

    @Test
    public void testSelectHosts_ExcludesStaleHeartbeat() {
        Host staleHost = Host.builder()
                .owner(testUser)
                .name("StaleHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now().minusMinutes(5)) // exceeds timeout
                .build();
        hostRepository.save(staleHost);

        StorageContainer container = StorageContainer.builder()
                .host(staleHost)
                .filePath("/tmp/container")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(container);

        List<Host> selected = strategy.selectHosts(1, 50L, Set.of());
        assertTrue(selected.isEmpty(), "Stale hosts must be excluded");
    }

    @Test
    public void testSelectHosts_ExcludesContainerFailures() {
        Host corruptedContainerHost = Host.builder()
                .owner(testUser)
                .name("CorruptedHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(corruptedContainerHost);

        StorageContainer container = StorageContainer.builder()
                .host(corruptedContainerHost)
                .filePath("/tmp/container")
                .totalSize(1000L)
                .status(StorageContainer.Status.CORRUPTED)
                .build();
        containerRepository.save(container);

        List<Host> selected = strategy.selectHosts(1, 50L, Set.of());
        assertTrue(selected.isEmpty(), "Corrupted containers must be excluded");
    }

    @Test
    public void testSelectHosts_ExcludesInsufficientCapacity() {
        Host lowCapHost = Host.builder()
                .owner(testUser)
                .name("LowCapHost")
                .totalCapacityBytes(100L)
                .reservedCapacityBytes(80L)
                .usedCapacityBytes(15L) // available = 5 bytes
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(lowCapHost);

        StorageContainer container = StorageContainer.builder()
                .host(lowCapHost)
                .filePath("/tmp/container")
                .totalSize(100L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(container);

        // Try to select for chunk of size 10 bytes
        List<Host> selected = strategy.selectHosts(1, 10L, Set.of());
        assertTrue(selected.isEmpty(), "Hosts with insufficient capacity must be excluded");
    }

    @Test
    public void testSelectHosts_ExcludesManuallyExcludedHosts() {
        Host host = Host.builder()
                .owner(testUser)
                .name("ExcludedHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(host);

        StorageContainer container = StorageContainer.builder()
                .host(host)
                .filePath("/tmp/container")
                .totalSize(1000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(container);

        List<Host> selected = strategy.selectHosts(1, 50L, Set.of(host.getId()));
        assertTrue(selected.isEmpty(), "Explicitly excluded hosts must not be selected");
    }

    @Test
    public void testComputeScore_RankingVerification() {
        // Host A: high storage, recent heartbeat
        Host hostA = Host.builder()
                .owner(testUser)
                .name("HostA")
                .totalCapacityBytes(10000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(1000L) // 90% available
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(hostA);

        StorageContainer containerA = StorageContainer.builder()
                .host(hostA)
                .filePath("/tmp/containerA")
                .totalSize(10000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(containerA);

        // Host B: lower storage, recent heartbeat
        Host hostB = Host.builder()
                .owner(testUser)
                .name("HostB")
                .totalCapacityBytes(10000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(8000L) // 20% available
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(hostB);

        StorageContainer containerB = StorageContainer.builder()
                .host(hostB)
                .filePath("/tmp/containerB")
                .totalSize(10000L)
                .status(StorageContainer.Status.ACTIVE)
                .build();
        containerRepository.save(containerB);

        double scoreA = strategy.computeScore(hostA, LocalDateTime.now());
        double scoreB = strategy.computeScore(hostB, LocalDateTime.now());

        assertTrue(scoreA > scoreB, "Host A (90% free) should score higher than Host B (20% free)");

        List<Host> selected = strategy.selectHosts(2, 50L, Set.of());
        assertEquals(2, selected.size());
        assertEquals("HostA", selected.get(0).getName(), "HostA should be preferred over HostB");
    }
}
