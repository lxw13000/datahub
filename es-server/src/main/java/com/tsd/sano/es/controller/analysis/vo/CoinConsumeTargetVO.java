package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 金币消费去处用户及其累计消费结果。
 *
 * @author lxw
 */
@Data
public class CoinConsumeTargetVO {

    /**
     * 金币消费接收者ID，对应金币流水ta_user_id。
     */
    private Integer targetUserId;

    /**
     * 向该用户消费的原始tokens累计值。
     */
    private Long tokens;
}
