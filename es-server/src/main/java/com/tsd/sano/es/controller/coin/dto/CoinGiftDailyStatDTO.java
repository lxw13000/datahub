package com.tsd.sano.es.controller.coin.dto;

import lombok.Data;

/**
 * 金币礼物日统计查询参数。
 *
 * @author lxw
 */
@Data
public class CoinGiftDailyStatDTO {

    /** 开始业务日期，格式 yyyy-MM-dd。 */
    private String startDate;

    /** 结束业务日期，格式 yyyy-MM-dd。 */
    private String endDate;
}
