package com.neurovault.backend.replication.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neurovault.backend.entity.Host;
import com.neurovault.backend.entity.StorageContainer;
import com.neurovault.backend.monitor.dto.ClusterStatistics;
import com.neurovault.backend.monitor.model.HostHealthInfo;
import com.neurovault.backend.monitor.model.HostHealthStatus;
import com.neurovault.backend.monitor.service.ClusterAnalyticsService;
import com.neurovault.backend.monitor.service.ClusterHealthMonitor;
import com.neurovault.backend.replication.config.ReplicationConfig;
import com.neurovault.backend.replication.dto.ClusterHealthResponse;
import com.neurovault.backend.replication.dto.ReplicaInfoDto;
import com.neurovault.backend.replication.dto.RepairResultDto;
import com.neurovault.backend.replication.service.ReplicationService;
import com.neurovault.backend.replication.service.SelfHealingService;
import com.neurovault.backend.repository.HostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_controller;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClusterAnalyticsService analyticsService;

    @MockBean
    private ClusterHealthMonitor healthMonitor;

    @MockBean
    private ReplicationService replicationService;

    @MockBean
    private SelfHealingService selfHealingService;

    @MockBean
    private HostRepository hostRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(username = "admin")
    public void testGetClusterStatus_Success() throws Exception {
        ClusterStatistics mockStats = ClusterStatistics.builder()
                .totalHosts(3)
                .onlineHosts(2)
                .offlineHosts(1)
                .totalStorageBytes(3000L)
                .usedStorageBytes(1000L)
                .availableStorageBytes(1500L)
                .totalFiles(5)
                .totalChunks(15)
                .totalReplicas(45)
                .activeReplicas(40)
                .missingReplicas(5)
                .corruptedReplicas(0)
                .repairCount(2)
                .recoveryCount(1)
                .averageReplicationFactor(2.67)
                .clusterUtilizationPercent(33.33)
                .timestamp(LocalDateTime.now())
                .build();

        when(analyticsService.computeStatistics()).thenReturn(mockStats);

        mockMvc.perform(get("/api/cluster/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalHosts").value(3))
                .andExpect(jsonPath("$.onlineHosts").value(2))
                .andExpect(jsonPath("$.offlineHosts").value(1))
                .andExpect(jsonPath("$.totalStorageBytes").value(3000))
                .andExpect(jsonPath("$.averageReplicationFactor").value(2.67));
    }

    @Test
    @WithMockUser(username = "admin")
    public void testGetAllHosts_Success() throws Exception {
        UUID hostId = UUID.randomUUID();
        Host mockHost = Host.builder()
                .id(hostId)
                .name("HostX")
                .deviceType("LAPTOP")
                .operatingSystem("Windows")
                .totalCapacityBytes(5000L)
                .usedCapacityBytes(1000L)
                .reservedCapacityBytes(500L)
                .status(Host.Status.ONLINE)
                .lastHeartbeat(LocalDateTime.now())
                .build();

        HostHealthInfo mockHealth = new HostHealthInfo(
                hostId, "HostX", Host.Status.ONLINE, HostHealthStatus.HEALTHY,
                12.5, 60.0, 5000L, 1000L, 3500L, 20.0,
                10, mockHost.getLastHeartbeat(), StorageContainer.Status.ACTIVE,
                Collections.emptyList()
        );

        when(hostRepository.findAll()).thenReturn(List.of(mockHost));
        when(healthMonitor.evaluateHostHealth(any(Host.class))).thenReturn(mockHealth);

        mockMvc.perform(get("/api/cluster/hosts"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].name").value("HostX"))
                .andExpect(jsonPath("$[0].deviceType").value("LAPTOP"))
                .andExpect(jsonPath("$[0].healthStatus").value("HEALTHY"))
                .andExpect(jsonPath("$[0].availableCapacityBytes").value(3500));
    }

    @Test
    @WithMockUser(username = "admin")
    public void testGetReplicaInfo_Success() throws Exception {
        UUID chunkId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        ReplicaInfoDto mockDto = ReplicaInfoDto.builder()
                .chunkId(chunkId)
                .fileId(fileId)
                .chunkIndex(0)
                .sizeBytes(100L)
                .currentReplicaCount(3)
                .targetReplicaCount(3)
                .underReplicated(false)
                .placements(Collections.emptyList())
                .build();

        when(replicationService.generateAllReplicaMetadata()).thenReturn(List.of(mockDto));

        mockMvc.perform(get("/api/cluster/replicas"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].chunkId").value(chunkId.toString()))
                .andExpect(jsonPath("$[0].currentReplicaCount").value(3))
                .andExpect(jsonPath("$[0].underReplicated").value(false));
    }

    @Test
    @WithMockUser(username = "admin")
    public void testTriggerManualRepair_Success() throws Exception {
        RepairResultDto mockResult = RepairResultDto.builder()
                .chunksInspected(5)
                .repairsInitiated(2)
                .repairsSucceeded(2)
                .repairsFailed(0)
                .details(List.of("Repair successful"))
                .timestamp(LocalDateTime.now())
                .build();

        when(selfHealingService.runHealingCycle()).thenReturn(mockResult);

        mockMvc.perform(post("/api/cluster/repair"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.chunksInspected").value(5))
                .andExpect(jsonPath("$.repairsSucceeded").value(2));
    }

    @Test
    @WithMockUser(username = "admin")
    public void testGetClusterHealth_Success() throws Exception {
        ClusterHealthResponse mockHealth = ClusterHealthResponse.builder()
                .healthLevel(ClusterHealthResponse.HealthLevel.HEALTHY)
                .healthyHostCount(5)
                .unhealthyHostCount(0)
                .underReplicatedChunks(0)
                .timestamp(LocalDateTime.now())
                .issues(Collections.emptyList())
                .build();

        when(healthMonitor.getOverallClusterHealth()).thenReturn(mockHealth);

        mockMvc.perform(get("/api/cluster/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.healthLevel").value("HEALTHY"))
                .andExpect(jsonPath("$.healthyHostCount").value(5));
    }
}
