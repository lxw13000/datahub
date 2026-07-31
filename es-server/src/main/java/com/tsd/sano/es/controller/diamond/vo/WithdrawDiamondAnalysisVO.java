package com.tsd.sano.es.controller.diamond.vo;

/**
 * 一个提现用户按余额变化方向和业务类型汇总的钻石数量。
 *
 * @param userId       用户ID
 * @param status       余额变化方向，1表示加余额，-1表示减余额
 * @param businessType 业务类型
 * @param tokens       钻石总数
 */
public record WithdrawDiamondAnalysisVO(
        long userId,
        int status,
        int businessType,
        long tokens) {
}
