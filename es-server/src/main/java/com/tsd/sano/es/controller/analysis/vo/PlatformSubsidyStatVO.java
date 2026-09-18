package com.tsd.sano.es.controller.analysis.vo;

import lombok.Data;

/**
 * 单个业务类型的平台补贴汇总结果。
 *
 * <p>补贴数值为钱包流水原始tokens汇总值，不在查询服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Data
public class PlatformSubsidyStatVO {

    /**
     * 平台补贴业务类型，对应EBusinessType.code。
     */
    private Integer businessType;

    /**
     * 该业务类型发放的补贴tokens累计值。
     */
    private Long tokens = 0L;
}
