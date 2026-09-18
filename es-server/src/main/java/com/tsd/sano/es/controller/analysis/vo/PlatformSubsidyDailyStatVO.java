package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单日各业务类型的平台补贴汇总结果。
 *
 * @author lxw
 */
@Data
public class PlatformSubsidyDailyStatVO {

    /**
     * 业务日期，格式yyyy-MM-dd。
     */
    private String dt;

    /**
     * 当日各平台补贴业务类型的tokens汇总。
     */
    private List<PlatformSubsidyStatVO> stats = new ArrayList<>();
}
