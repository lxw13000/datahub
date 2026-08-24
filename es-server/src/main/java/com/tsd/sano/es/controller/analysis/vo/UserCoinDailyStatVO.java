package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 用户每日金币消费和返奖统计。
 *
 * <p>所有数值均为金币流水原始tokens汇总值，不在查询服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Data
public class UserCoinDailyStatVO {

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

    /**
     * 商城道具消费金币数。
     */
    private Long mallPropConsumeTokens = 0L;

    /**
     * VIP消费金币数。
     */
    private Long vipConsumeTokens = 0L;

    /**
     * 幸运礼物返奖金币数。
     */
    private Long luckyGiftRewardTokens = 0L;

    /**
     * 游戏返奖金币数。
     */
    private Long gameRewardTokens = 0L;

    /**
     * 幸运礼物Jackpot中奖金币数。
     */
    private Long luckyGiftJackpotTokens = 0L;

    /**
     * 游戏Jackpot中奖金币数。
     */
    private Long gameJackpotTokens = 0L;
}
