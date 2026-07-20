package com.neurovault.backend.coordinator;

import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.User;
import com.neurovault.backend.repository.HostRepository;
import com.neurovault.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit/Integration tests for {@link CoordinatorService}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coordinatortestdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
class CoordinatorServiceTest {

    @Autowired
    private CoordinatorService coordinatorService;

    @Autowired
    private HostRepository hostRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        hostRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("coorduser")
                .email("coord@test.com")
                .password("password")
                .role(User.Role.CLIENT)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("selectTargetHosts should return online hosts")
    void testSelectTargetHosts() {
        Host host1 = Host.builder()
                .owner(testUser)
                .name("host-1")
                .status(Host.Status.ONLINE)
                .totalCapacityBytes(1000000000L)
                .reservedCapacityBytes(500000000L)
                .usedCapacityBytes(0L)
                .build();
        hostRepository.save(host1);

        List<Host> hosts = coordinatorService.selectTargetHosts(1);
        assertFalse(hosts.isEmpty());
        assertEquals("host-1", hosts.get(0).getName());
    }

    @Test
    @DisplayName("generateChunkToken should return signed non-empty JWT token")
    void testGenerateChunkToken() {
        String token = coordinatorService.generateChunkToken(UUID.randomUUID(), UUID.randomUUID(), 0);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }
}
