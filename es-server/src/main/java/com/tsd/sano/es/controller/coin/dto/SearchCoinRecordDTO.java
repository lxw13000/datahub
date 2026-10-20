package com.tsd.sano.es.controller.coin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 金币记录查询参数
 */
@Data
public class SearchCoinRecordDTO {

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 对方用户ID。
     */
    private Integer taUserId;

    /**
     * 房间ID。
     */
    private Integer roomId;
    /**
     * 礼物ID。
     */
    private Integer propId;

    /**
     * 业务类型
     */
    private Long businessType;
    /**
     * 业务类型集合
     */
    private List<Long> businessCodes;
    /**
     * 开始时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String startTime;

    /**
     * 结束时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String endTime;

    /**
     * 搜索类型，0:深度分页，1:普通分页（普通分页最多查询10000条数据，超过10000条数据请使用深度分页）
     */
    private Integer searchType;

    /**
     * 页码索引，从1开始
     */
    private Integer pageIndex;

    /**
     * 分页大小
     */
    private Integer pageSize;

    /**
     * 上次查询的最后一条记录创建时间
     */
    private String lastCreateTime;
    /**
     * 上次查询的最后一条记录id
     */
    private Long lastId;

    /**
     * 合并业务类型集合与单值参数，不修改原始入参。
     */
    public List<Long> realBusinessCodes() {
        List<Long> codes = businessCodes == null
                ? new ArrayList<>()
                : new ArrayList<>(businessCodes);
        if (businessType != null && !codes.contains(businessType)) {
            codes.add(businessType);
        }
        return codes;
    }
}
