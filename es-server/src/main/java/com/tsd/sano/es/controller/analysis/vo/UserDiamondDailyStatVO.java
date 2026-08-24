package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 用户每日钻石收入统计。
 *
 * <p>所有数值均为钻石流水原始tokens汇总值，不在查询服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Data
public class UserDiamondDailyStatVO {

    /**
     * 业务日期，格式yyyy-MM-dd。
     */
    private String dt;

    /**
     * 普通礼物收入钻石数。
     */
    private Long normalGiftIncomeTokens = 0L;

    /**
     * 幸运礼物收入钻石数。
     */
    private Long luckyGiftIncomeTokens = 0L;

    /**
     * 游戏收入钻石数。
     */
    private Long gameIncomeTokens = 0L;
}
