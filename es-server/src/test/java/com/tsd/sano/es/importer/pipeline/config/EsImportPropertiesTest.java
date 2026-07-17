package com.tsd.sano.es.importer.pipeline.config;

import com.tsd.sano.es.sync.config.TableSyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ES导入配置绑定、表分组和兼容性测试。
 */
class EsImportPropertiesTest {

    /**
     * 所有环境配置和示例配置都必须能被应用实际使用的Spring YAML加载器解析。
     */
    @Test
    void shouldParseAllApplicationYamlFiles() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : List.of(
                "application-dev.yml",
                "application-test.yml",
                "application-prod.yml",
                "application-import-example.yml")) {
            assertThat(loader.load(resource, new ClassPathResource(resource))).isNotEmpty();
        }
    }

    /**
     * 表配置加载后应一次性生成T+1和polling两个只读集合。
     */
    @Test
    void shouldBuildEnabledTableListsWhenTablesAreLoaded() {
        EsImportProperties properties = new EsImportProperties();
        properties.setTables(List.of(table("coin", null), table("lucky", TableSyncMode.POLLING)));

        assertThat(properties.getTPlusOneTables())
                .extracting(EsImportProperties.TableConfig::getIndexAlias)
                .containsExactly("coin");
        assertThat(properties.getPollingTables())
                .extracting(EsImportProperties.TableConfig::getIndexAlias)
                .containsExactly("lucky");
        assertThatThrownBy(() -> properties.getTPlusOneTables().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * YAML中的模式、启动日期和共享写入参数必须由同一个Properties完成绑定。
     */
    @Test
    void shouldBindTablesAndSharedWriteProperties() {
        Map<String, String> values = Map.ofEntries(
                Map.entry("tables[0].index-alias", "coin"),
                Map.entry("tables[0].table-name", "coin"),
                Map.entry("tables[0].mapping-file", "coin.json"),
                Map.entry("tables[0].sync-mode", "t-plus-one"),
                Map.entry("tables[1].index-alias", "lucky"),
                Map.entry("tables[1].table-name", "lucky"),
                Map.entry("tables[1].mapping-file", "lucky.json"),
                Map.entry("tables[1].sync-mode", "polling"),
                Map.entry("tables[1].bootstrap-start-date", "2026-07-16"),
                Map.entry("common.write.global-bulk-concurrency", "5"),
                Map.entry("t-plus-one.enabled", "true"),
                Map.entry("t-plus-one.read-batch-size", "1234")
        );

        EsImportProperties bound = new Binder(new MapConfigurationPropertySource(values))
                .bind("", Bindable.of(EsImportProperties.class))
                .orElseThrow(() -> new AssertionError("ES import properties were not bound"));

        assertThat(bound.getTPlusOneTables()).hasSize(1);
        assertThat(bound.getPollingTables()).hasSize(1);
        assertThat(bound.getPollingTables().get(0).getBootstrapStartDate())
                .isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(bound.getCommon().getWrite().getGlobalBulkConcurrency()).isEqualTo(5);
        assertThat(bound.getCommon().getWrite().getGlobalQueueMaxBytes())
                .isEqualTo(DataSize.ofMegabytes(128));
        assertThat(bound.getTPlusOne().isEnabled()).isTrue();
        assertThat(bound.getTPlusOne().getReadBatchSize()).isEqualTo(1234);
    }

    /**
     * 两条启用配置使用同一Alias时必须在配置加载阶段失败。
     */
    @Test
    void shouldRejectDuplicateEnabledAlias() {
        EsImportProperties properties = new EsImportProperties();

        assertThatThrownBy(() -> properties.setTables(List.of(
                table("coin", TableSyncMode.T_PLUS_ONE),
                table("coin", TableSyncMode.POLLING))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate enabled ES sync index-alias");
    }

    private EsImportProperties.TableConfig table(String alias, TableSyncMode syncMode) {
        EsImportProperties.TableConfig table = new EsImportProperties.TableConfig();
        table.setIndexAlias(alias);
        table.setTableName(alias);
        table.setMappingFile(alias + ".json");
        table.setSyncMode(syncMode);
        return table;
    }
}
