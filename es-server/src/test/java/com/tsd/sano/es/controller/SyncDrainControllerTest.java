package com.tsd.sano.es.controller;

import com.tsd.sano.es.importer.task.EsImportTask;
import com.tsd.sano.es.sync.service.GlobalEsWritePermitManager;
import com.tsd.sano.es.sync.service.GlobalSyncMemoryLimiter;
import com.tsd.sano.es.sync.service.SyncDrainCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一同步排空管理接口契约测试。
 */
class SyncDrainControllerTest {

    /**
     * 三个部署接口必须保持固定路径，并分别暴露协调器状态和drain结果。
     */
    @Test
    void shouldExposeDrainStatusAndCancelContract() throws Exception {
        SyncDrainCoordinator coordinator = mock(SyncDrainCoordinator.class);
        EsImportTask importTask = mock(EsImportTask.class);
        SyncDrainCoordinator.DrainStatusSnapshot snapshot = snapshot();
        when(coordinator.startDrain()).thenReturn(snapshot);
        when(coordinator.status()).thenReturn(snapshot);
        when(coordinator.cancelDrain("operation-1")).thenReturn(snapshot);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new SyncDrainController(coordinator, importTask)).build();

        mockMvc.perform(post("/internal/sync/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("operation-1"))
                .andExpect(jsonPath("$.data.coordinatorState").value("DRAINING"))
                .andExpect(jsonPath("$.data.drainResult").value("IN_PROGRESS"));

        mockMvc.perform(get("/internal/sync/drain/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tPlusOne.persistentTask.taskStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.tPlusOne.lastSafeCheckpointId").value(120));

        mockMvc.perform(post("/internal/sync/drain/cancel")
                        .param("operationId", "operation-1"))
                .andExpect(status().isOk());
        verify(importTask).resumeAfterDrainCancel();
    }

    private SyncDrainCoordinator.DrainStatusSnapshot snapshot() {
        SyncDrainCoordinator.PersistentTaskSnapshot task =
                new SyncDrainCoordinator.PersistentTaskSnapshot(
                        "coin_20260715", "coin", "coin", "20260715",
                        "RUNNING", 120L, false, null);
        SyncDrainCoordinator.TPlusOneRuntimeSnapshot runtime =
                new SyncDrainCoordinator.TPlusOneRuntimeSnapshot(
                        true, true, true, task, false,
                        2, 1, 0, 3L, 1L, 130L, 120L);
        SyncDrainCoordinator.ResourceSnapshot resources =
                new SyncDrainCoordinator.ResourceSnapshot(
                        new GlobalEsWritePermitManager.Snapshot(1, 0, 1, 0, 0),
                        new GlobalSyncMemoryLimiter.Snapshot(1024L, 512L, 1));
        return new SyncDrainCoordinator.DrainStatusSnapshot(
                "ALL",
                SyncDrainCoordinator.CoordinatorState.DRAINING,
                SyncDrainCoordinator.DrainResult.IN_PROGRESS,
                "operation-1",
                Instant.parse("2026-07-16T10:00:00Z"),
                null,
                null,
                runtime,
                resources
        );
    }
}
