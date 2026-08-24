package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 钻石收入来源用户及其累计收入结果。
 *
 * @author lxw
 */
@Data
public class DiamondIncomeSourceVO {

    /**
     * 钻石收入赠送者ID，对应钻石流水ta_user_id。
     */
    private Integer sourceUserId;

    /**
     * 来自该用户的原始tokens累计值。
     */
    private Long tokens;
}
