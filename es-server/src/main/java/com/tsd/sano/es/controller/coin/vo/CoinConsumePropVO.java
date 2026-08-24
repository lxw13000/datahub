package com.tsd.sano.es.controller.coin.vo;

import lombok.Data;

/**
 * 具体金币消费类型及其累计消费结果。
 *
 * @author lxw
 */
@Data
public class CoinConsumePropVO {

    /**
     * 具体消费ID，对应金币流水prop_id。
     */
    private Integer propId;

    /**
     * 该消费类型的原始tokens累计值。
     */
    private Long tokens;
}
