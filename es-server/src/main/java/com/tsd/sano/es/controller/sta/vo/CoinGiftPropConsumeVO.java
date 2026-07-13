package com.tsd.sano.es.controller.sta.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个幸运礼物的消费金额。
 *
 * @author lxw
 */
@Data
public class CoinGiftPropConsumeVO {

    /** 礼物 ID。 */
    private Long propId;

    /** 消费金额，单位美元。 */
    private BigDecimal consumeDollar;
}
