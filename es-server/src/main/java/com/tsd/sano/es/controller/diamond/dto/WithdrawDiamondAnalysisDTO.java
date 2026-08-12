package com.tsd.sano.es.controller.diamond.dto;

import lombok.Data;

import java.util.List;

/**
 * 提现用户钻石来源和用途分析参数。
 *
 * <p>提现时间范围只用于筛选用户，钻石统计时间范围只用于查询钻石流水，
 * 两组时间相互独立。</p>
 */
@Data
public class WithdrawDiamondAnalysisDTO {

    /**
     * 用户类型，1.注册用户，2提现用户。
     */
    private Integer userType = 2;

    /**
     * 提现开始时间，格式为yyyy-MM-dd HH:mm:ss，包含该时间。
     */
    private String withdrawStartTime;

    /**
     * 提现结束时间，格式为yyyy-MM-dd HH:mm:ss，包含该时间。
     */
    private String withdrawEndTime;

    /**
     * 钻石统计开始时间，格式为yyyy-MM-dd HH:mm:ss，包含该时间。
     */
    private String statisticsStartTime;

    /**
     * 钻石统计结束时间，格式为yyyy-MM-dd HH:mm:ss，包含该时间。
     */
    private String statisticsEndTime;

    /**
     * 排除的业务类型列表，钻石流水中属于这些业务类型的记录将被排除在统计之外。
     */
    private List<Integer> excludeBusinessTypes;
}
