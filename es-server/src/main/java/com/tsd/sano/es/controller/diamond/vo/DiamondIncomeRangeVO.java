package com.tsd.sano.es.controller.diamond.vo;

/**
 * 一个钻石收入区间及其用户数量。
 *
 * @param incomeRange 收入区间
 * @param userCount   区间内用户数
 */
public record DiamondIncomeRangeVO(String incomeRange, long userCount) {
}
