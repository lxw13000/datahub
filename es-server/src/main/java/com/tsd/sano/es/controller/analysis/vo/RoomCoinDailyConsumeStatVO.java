package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 房间集合每日金币消费统计。
 *
 * <p>所有数值均为金币流水原始tokens汇总值，不在查询服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Data
public class RoomCoinDailyConsumeStatVO {

    /**
     * 业务日期，格式yyyy-MM-dd。
     */
    private String dt;

    /**
     * 普通礼物消费金币数。
     */
    private Long normalGiftConsumeTokens = 0L;

    /**
     * 幸运礼物消费金币数。
     */
    private Long luckyGiftConsumeTokens = 0L;

    /**
     * 游戏消费金币数。
     */
    private Long gameConsumeTokens = 0L;
}
