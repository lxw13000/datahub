package com.tsd.sano.es.controller.sta.vo;

/**
 * 金币周统计结果。
 */
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
     * 幸运礼物消费流水。
     */
    private long luckyGiftTokens = 0L;

    /**
     * 游戏消费流水。
     */
    private long gameTokens = 0L;

    public long getConsumeUserCount() {
        return consumeUserCount;
    }

    public void setConsumeUserCount(long consumeUserCount) {
        this.consumeUserCount = consumeUserCount;
    }

    public long getConsumeTokens() {
        return consumeTokens;
    }

    public void setConsumeTokens(long consumeTokens) {
        this.consumeTokens = consumeTokens;
    }

    public long getLuckyGiftTokens() {
        return luckyGiftTokens;
    }

    public void setLuckyGiftTokens(long luckyGiftTokens) {
        this.luckyGiftTokens = luckyGiftTokens;
    }

    public long getGameTokens() {
        return gameTokens;
    }

    public void setGameTokens(long gameTokens) {
        this.gameTokens = gameTokens;
    }

    @Override
    public String toString() {
        return "WeekStat{" +
                "consumeUserCount=" + consumeUserCount +
                ", consumeTokens=" + consumeTokens +
                ", luckyGiftTokens=" + luckyGiftTokens +
                ", gameTokens=" + gameTokens +
                '}';
    }
}