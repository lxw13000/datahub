package com.tsd.sano.es.controller.analysis.dto;

import lombok.Data;

/**
 * 用户钻石收入分析查询参数。
 *
 * @author lxw
 */
@Data
public class UserDiamondAnalysisDTO {

    /**
     * 需要统计的用户ID。
     */
    private Long userId;

    /**
     * 统计开始业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String startDate;

    /**
     * 统计结束业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String endDate;
}
