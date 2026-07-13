package com.tsd.sano.es.controller.sta.vo;

import lombok.Data;

import java.util.List;

/**
 * 每日幸运礼物消费金额统计结果。
 *
 * @author lxw
 */
@Data
public class CoinGiftDailyPropStatVO {

    /** 业务日期。 */
    private String dt;

    /** 当日消费金额最高的幸运礼物，最多 20 个。 */
    private List<CoinGiftPropConsumeVO> props;
}
