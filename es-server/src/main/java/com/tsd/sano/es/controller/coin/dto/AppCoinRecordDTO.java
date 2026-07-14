package com.tsd.sano.es.controller.coin.dto;

import lombok.Data;

/**
 * app个人中心金币记录查询参数
 */
@Data
public class AppCoinRecordDTO {

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 业务类型
     */
    private Long businessType;

    /**
     * 开始时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String startTime;

    /**
     * 结束时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String endTime;

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
}
