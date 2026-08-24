package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户各类具体金币消费Top10。
 *
 * @author lxw
 */
@Data
public class CoinConsumePropTopVO {

    /**
     * 普通礼物消费类型Top10。
     */
    private List<CoinConsumePropVO> normalGift = new ArrayList<>();

    /**
     * 幸运礼物消费类型Top10。
     */
    private List<CoinConsumePropVO> luckyGift = new ArrayList<>();

    /**
     * 游戏消费类型Top10。
     */
    private List<CoinConsumePropVO> game = new ArrayList<>();
}
