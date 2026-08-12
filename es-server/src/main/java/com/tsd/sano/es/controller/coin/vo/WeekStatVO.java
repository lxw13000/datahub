package com.tsd.sano.es.controller.coin.vo;

import lombok.Data;

/**
 * 金币周统计结果。
 */
@Data
public class WeekStatVO {

    /**
     * 消费人数，按user_id去重。
     */
    private long consumeUserCount = 0L;

    /**
     * 消费总流水。
     */
    private long consumeTokens = 0L;
    /**
     * 普通礼物消费流水。
     */
    private long normalGiftTokens = 0L;
    /**
     * 幸运礼物消费流水。
     */
    private long luckyGiftTokens = 0L;

    /**
     * 游戏消费流水。
     */
    private long gameTokens = 0L;
}
