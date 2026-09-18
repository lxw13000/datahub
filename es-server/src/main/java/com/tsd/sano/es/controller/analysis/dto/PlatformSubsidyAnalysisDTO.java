package com.tsd.sano.es.controller.analysis.dto;

import lombok.Data;

/**
 * 平台补贴统计查询参数。
 *
 * @author lxw
 */
@Data
public class PlatformSubsidyAnalysisDTO {

    /**
     * 统计开始业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String startDate;

    /**
     * 统计结束业务日期，格式yyyy-MM-dd，包含当天。
     */
    private String endDate;
}
