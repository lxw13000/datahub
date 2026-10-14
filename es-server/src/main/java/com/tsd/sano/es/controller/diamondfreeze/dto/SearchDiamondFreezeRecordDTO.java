package com.tsd.sano.es.controller.diamondfreeze.dto;

import lombok.Data;

/**
 * 钻石冻结记录查询参数。
 */
@Data
public class SearchDiamondFreezeRecordDTO {

    /** 流水ID。 */
    private Long id;

    /** 公共ID。 */
    private Long commonId;

    /** 冻结记录所属用户ID。 */
    private Long userId;

    /** 消费者ID。 */
    private Integer consumeUserId;

    /** 主播ID。 */
    private Integer anchorId;

    /** 业务类型。 */
    private Integer businessType;

    /** 汇总类型。 */
    private Integer freezeType;

    /** 礼物ID。 */
    private Integer propId;

    /** 房间ID。 */
    private Integer roomId;

    /** 开始时间，格式为yyyy-MM-dd HH:mm:ss。 */
    private String startTime;

    /** 结束时间，格式为yyyy-MM-dd HH:mm:ss。 */
    private String endTime;

    /** 搜索类型：0为search_after深度分页，1为普通分页。 */
    private Integer searchType = 1;

    /** 普通分页页码，从1开始。 */
    private Integer pageIndex = 1;

    /** 每页记录数。 */
    private Integer pageSize = 20;

    /** 深度分页时上一页最后一条记录的创建时间。 */
    private String lastCreateTime;

    /** 深度分页时上一页最后一条记录的ID。 */
    private Long lastId;
}
