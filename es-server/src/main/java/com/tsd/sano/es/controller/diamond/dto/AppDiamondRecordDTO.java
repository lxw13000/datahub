package com.tsd.sano.es.controller.diamond.dto;

import lombok.Data;

/**
 * App 个人钻石记录查询参数
 *
 * @author lxw
 */
@Data
public class AppDiamondRecordDTO {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 业务类型
     */
    private Long businessType;

    /**
     * 开始时间，格式 yyyy-MM-dd HH:mm:ss
     */
    private String startTime;

    /**
     * 结束时间，格式 yyyy-MM-dd HH:mm:ss
     */
    private String endTime;

    /**
     * 每页记录数
     */
    private Integer pageSize;

    /**
     * 上一页最后一条记录的创建时间
     */
    private String lastCreateTime;

    /**
     * 上一页最后一条记录的 ID
     */
    private Long lastId;
}
