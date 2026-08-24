package com.tsd.sano.es.controller.analysis.dto;

import lombok.Data;

import java.util.List;

/**
 * 房间集合金币、钻石分析查询参数。
 *
 * @author lxw
 */
@Data
public class RoomWalletAnalysisDTO {

    /**
     * 需要合并统计的房间ID集合。
     */
    private List<Integer> roomIds;

    /**
     * 统计开始业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String startDate;

    /**
     * 统计结束业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String endDate;
}
