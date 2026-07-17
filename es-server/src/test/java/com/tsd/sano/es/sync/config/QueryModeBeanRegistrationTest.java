package com.tsd.sano.es.sync.config;

import com.tsd.sano.es.controller.EsImportController;
import com.tsd.sano.es.controller.SyncDrainController;
import com.tsd.sano.es.controller.coin.WalletCoinController;
import com.tsd.sano.es.controller.diamond.WalletDiamondController;
import com.tsd.sano.es.importer.task.EsImportTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * query模式Bean集合验证：模式只关闭能力，不裁剪同步Bean。
 */
@SpringBootTest(properties = {
        "sano.server-mode=query",
        "sano.import.t-plus-one.enabled=false"
})
@ActiveProfiles("test")
class QueryModeBeanRegistrationTest {

    @Autowired
    private EsServiceModeManager serviceModeManager;

    @Autowired
    private EsImportTask esImportTask;

    @Autowired
    private EsImportController esImportController;

    @Autowired
    private SyncDrainController syncDrainController;

    @Autowired
    private WalletCoinController walletCoinController;

    @Autowired
    private WalletDiamondController walletDiamondController;

    @Autowired
    @Qualifier("esImportExecutor")
    private Executor esImportExecutor;

    @Test
    void shouldRegisterSameQueryAndSyncBeansWhileSyncCapabilityIsDisabled() {
        assertThat(serviceModeManager.currentMode()).isEqualTo(EsServiceMode.QUERY);
        assertThat(serviceModeManager.isSyncEnabled()).isFalse();
        assertThat(esImportTask).isNotNull();
        assertThat(esImportController).isNotNull();
        assertThat(syncDrainController).isNotNull();
        assertThat(walletCoinController).isNotNull();
        assertThat(walletDiamondController).isNotNull();
        assertThat(esImportExecutor).isNotNull();
    }
}
