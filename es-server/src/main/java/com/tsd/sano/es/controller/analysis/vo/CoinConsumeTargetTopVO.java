package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户各类金币消费去处Top10。
 *
 * @author lxw
 */
@Data
public class CoinConsumeTargetTopVO {

    /**
     * 普通礼物消费接收者Top10。
     */
    private List<CoinConsumeTargetVO> normalGift = new ArrayList<>();

    /**
     * 幸运礼物消费接收者Top10。
     */
    private List<CoinConsumeTargetVO> luckyGift = new ArrayList<>();

    /**
     * 游戏消费接收者Top10。
     */
    private List<CoinConsumeTargetVO> game = new ArrayList<>();
}
