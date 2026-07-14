package com.tsd.sano.es.controller.coin.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 金币礼物日统计结果。
 *
 * @author lxw
 */
@Data
public class CoinGiftDailyStatVO {

    /** 业务日期。 */
    private String dt;
    /** 消费用户数，按 user_id 去重。 */
    private long userCount;
    /** 消费礼物数量。 */
    private long consumeAmount;
    /** 平均消费次数。 */
    private BigDecimal avgAmount;
    /** 消费金额，单位美元。 */
    private BigDecimal consumeDollar;
    /** 返奖金额，单位美元。 */
    private BigDecimal rewardDollar;
    /** 主播分层金额，单位美元。 */
    private BigDecimal hostDollar;
    /** Jackpot 金额，单位美元。 */
    private BigDecimal jackpotDollar;
    /** 净消耗金额，单位美元。 */
    private BigDecimal netDollar;
    /** 人均消费金额，单位美元。 */
    private BigDecimal avgDollar;
    /** Top3 消费金额，单位美元。 */
    private BigDecimal top3TotalDollar;
    /** Top3 礼物数量。 */
    private long top3TotalAmount;
    /** Top3 消费金额占比，百分比。 */
    private BigDecimal top3Ratio;
    /** Top1 礼物 ID。 */
    private Long top1Id;
    /** Top1 消费金额，单位美元。 */
    private BigDecimal top1Dollar;
    /** Top1 礼物数量。 */
    private Long top1Amount;
    /** Top2 礼物 ID。 */
    private Long top2Id;
    /** Top2 消费金额，单位美元。 */
    private BigDecimal top2Dollar;
    /** Top2 礼物数量。 */
    private Long top2Amount;
    /** Top3 礼物 ID。 */
    private Long top3Id;
    /** Top3 消费金额，单位美元。 */
    private BigDecimal top3Dollar;
    /** Top3 礼物数量。 */
    private Long top3Amount;
}
