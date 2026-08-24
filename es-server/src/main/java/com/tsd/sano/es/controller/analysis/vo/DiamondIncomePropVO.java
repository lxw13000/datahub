package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 具体钻石收入类型及其累计收入结果。
 *
 * @author lxw
 */
@Data
public class DiamondIncomePropVO {

    /**
     * 具体收入ID，对应钻石流水prop_id。
     */
    private Integer propId;

    /**
     * 该收入类型的原始tokens累计值。
     */
    private Long tokens;
}
