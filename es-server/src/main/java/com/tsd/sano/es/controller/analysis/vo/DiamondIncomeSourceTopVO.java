package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户各类钻石收入来源用户Top10。
 *
 * @author lxw
 */
@Data
public class DiamondIncomeSourceTopVO {

    /**
     * 普通礼物收入来源用户Top10。
     */
    private List<DiamondIncomeSourceVO> normalGift = new ArrayList<>();

    /**
     * 幸运礼物收入来源用户Top10。
     */
    private List<DiamondIncomeSourceVO> luckyGift = new ArrayList<>();

    /**
     * 游戏收入来源用户Top10。
     */
    private List<DiamondIncomeSourceVO> game = new ArrayList<>();
}
