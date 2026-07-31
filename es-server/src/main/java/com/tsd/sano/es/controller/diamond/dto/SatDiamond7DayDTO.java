package com.tsd.sano.es.controller.diamond.dto;

import lombok.Data;

/**
 * 钻石近7天收入
 */
@Data
public class SatDiamond7DayDTO {


    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 开始时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String startTime;

    /**
     * 结束时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String endTime;

    /**
     * 是否统计74业务收入（新人奖励），true表示统计，false表示不统计。
     */
    private boolean sta74 = false;
}
