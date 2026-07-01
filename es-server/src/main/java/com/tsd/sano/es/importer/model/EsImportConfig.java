package com.tsd.sano.es.importer.model;

import java.time.LocalDate;

/**
 * 一次导入任务配置
 * <p>
 * 一个对象代表一个业务表
 *
 * @author lxw
 */
public class EsImportConfig {

    /**
     * Alias
     */
    private String indexAlias;

    /**
     * 实际Index
     */
    private String indexName;

    /**
     * Mapping文件
     */
    private String mappingFile;

    /**
     * 数据表
     */
    private String tableName;

    /**
     * 导入日期
     */
    private LocalDate importDate;

    /**
     * SQL条件
     */
    private String whereSql;

    /**
     * 主键字段，用于游标分页和ES文档ID
     */
    private String idColumn = "id";

    /**
     * 分区日期字段，whereSql为空时默认按该字段做T+1过滤
     */
    private String dtColumn = "dt";

    /**
     * 起始游标ID，用于断点续跑时从 last_success_id 后继续读取
     */
    private long startId;

    public String getIndexAlias() {
        return indexAlias;
    }

    public void setIndexAlias(String indexAlias) {
        this.indexAlias = indexAlias;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getMappingFile() {
        return mappingFile;
    }

    public void setMappingFile(String mappingFile) {
        this.mappingFile = mappingFile;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public LocalDate getImportDate() {
        return importDate;
    }

    public void setImportDate(LocalDate importDate) {
        this.importDate = importDate;
    }

    public String getWhereSql() {
        return whereSql;
    }

    public void setWhereSql(String whereSql) {
        this.whereSql = whereSql;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public void setIdColumn(String idColumn) {
        this.idColumn = idColumn;
    }

    public String getDtColumn() {
        return dtColumn;
    }

    public void setDtColumn(String dtColumn) {
        this.dtColumn = dtColumn;
    }

    public long getStartId() {
        return startId;
    }

    public void setStartId(long startId) {
        this.startId = startId;
    }
}
