package com.tsd.sano.es.controller.diamond.dto;

import lombok.Data;

/**
 * 钻石流水记录查询参数
 *
 * @author lxw
 */
@Data
public class SearchDiamondRecordDTO {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 对方用户 ID
     */
    private Integer taUserId;

    /**
     * 房间 ID
     */
    private Integer roomId;

    /**
     * 礼物 ID
     */
    private Integer propId;

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
     * 搜索类型：0 为 search_after 深度分页，1 为普通分页
     */
    private Integer searchType;

    /**
     * 普通分页页码，从 1 开始
     */
    private Integer pageIndex;

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
