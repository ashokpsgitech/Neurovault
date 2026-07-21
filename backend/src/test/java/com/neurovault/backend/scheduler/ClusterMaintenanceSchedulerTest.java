package com.neurovault.backend.scheduler;

import com.neurovault.backend.entity.*;
import com.neurovault.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_scheduler;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
public class ClusterMaintenanceSchedulerTest {

    @Autowired
    private ClusterMaintenanceScheduler scheduler;

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
                .username("scheduser")
                .email("sched@example.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        userRepository.save(testUser);
    }

    @Test
    public void testCleanupTimedOutHosts_TransitionToOffline() {
        // Create an ONLINE host with heartbeat from 5 minutes ago
        Host timedOutHost = Host.builder()
                .owner(testUser)
                .name("StaleHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now().minusMinutes(5))
                .build();
        hostRepository.save(timedOutHost);

        // Run cleanup
        scheduler.cleanupTimedOutHosts();

        // Host status must change to OFFLINE
        Host updatedHost = hostRepository.findById(timedOutHost.getId()).orElseThrow();
        assertEquals(Host.Status.OFFLINE, updatedHost.getStatus());
    }

    @Test
    public void testCleanupTimedOutHosts_NoChangeForHealthyHost() {
        // Create a host with a very recent heartbeat
        Host healthyHost = Host.builder()
                .owner(testUser)
                .name("FreshHost")
                .totalCapacityBytes(1000L)
                .reservedCapacityBytes(0L)
                .usedCapacityBytes(0L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();
        hostRepository.save(healthyHost);

        // Run cleanup
        scheduler.cleanupTimedOutHosts();

        // Status should remain ONLINE
        Host updatedHost = hostRepository.findById(healthyHost.getId()).orElseThrow();
        assertEquals(Host.Status.ONLINE, updatedHost.getStatus());
    }
}
