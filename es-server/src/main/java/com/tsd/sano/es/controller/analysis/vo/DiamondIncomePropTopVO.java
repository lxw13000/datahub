package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户各类具体钻石收入Top10。
 *
 * @author lxw
 */
@Data
public class DiamondIncomePropTopVO {

    /**
     * 普通礼物收入类型Top10。
     */
    private List<DiamondIncomePropVO> normalGift = new ArrayList<>();

    /**
     * 幸运礼物收入类型Top10。
     */
    private List<DiamondIncomePropVO> luckyGift = new ArrayList<>();

    /**
     * 游戏收入类型Top10。
     */
    private List<DiamondIncomePropVO> game = new ArrayList<>();
}
