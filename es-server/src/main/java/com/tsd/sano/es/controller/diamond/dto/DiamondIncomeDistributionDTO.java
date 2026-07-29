package com.tsd.sano.es.controller.diamond.dto;

import lombok.Data;

/**
 * 按注册时间统计用户钻石收入分布的查询参数。
 */
@Data
public class DiamondIncomeDistributionDTO {

    /**
     * 用户注册开始时间，格式为yyyy-MM-dd HH:mm:ss，包含该时间。
     */
    private String startTime;

    /**
     * 用户注册结束时间，格式为yyyy-MM-dd HH:mm:ss，不包含该时间。
     */
    private String endTime;

    /**
     * 是否统计74业务收入（新人奖励），true表示统计，false表示不统计。
     */
    private boolean sta74 = false;
}
