package com.tsd.sano.es.controller.coin.dto;

import lombok.Data;

import java.util.List;

/**
 * 金币周统计查询参数。
 */
@Data
public class SatCoinWeekDTO {

    /**
     * 房间ID集合。
     */
    private List<Integer> roomIds;

    /**
     * 开始时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String startTime;

    /**
     * 结束时间，格式：yyyy-MM-dd HH:mm:ss。
     */
    private String endTime;
}
