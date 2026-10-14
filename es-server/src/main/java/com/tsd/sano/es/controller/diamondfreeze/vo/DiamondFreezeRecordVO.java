package com.tsd.sano.es.controller.diamondfreeze.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.tsd.sano.es.core.util.EsLocalDateTimeDeserializer;
import com.tsd.sano.es.core.util.SpaceLocalDateTimeSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钻石冻结记录查询结果。
 */
@Data
public class DiamondFreezeRecordVO {

    /** 流水ID。 */
    private Long id;

    /** 用户ID。 */
    @JsonAlias("user_id")
    private Long userId;

    /** 消费者ID。 */
    @JsonAlias("consume_user_id")
    private Integer consumeUserId;

    /** 主播ID。 */
    @JsonAlias("anchor_id")
    private Integer anchorId;

    /** 公共ID。 */
    @JsonAlias("common_id")
    private Long commonId;

    /** 创建时间，读取ES时兼容T格式，接口返回空格分隔格式。 */
    @JsonAlias("create_time")
    @JsonDeserialize(using = EsLocalDateTimeDeserializer.class)
    @JsonSerialize(using = SpaceLocalDateTimeSerializer.class)
    private LocalDateTime createTime;

    /** 钻石数量。 */
    private Long tokens;

    /** 变化后钻石数量。 */
    @JsonAlias("tokens_new")
    private Long tokensNew;

    /** 变化前钻石数量。 */
    @JsonAlias("tokens_old")
    private Long tokensOld;

    /** 业务类型。 */
    @JsonAlias("business_type")
    private Integer businessType;

    /** 汇总类型。 */
    @JsonAlias("freeze_type")
    private Integer freezeType;

    /** 数量。 */
    private Integer amount;

    /** 礼物ID。 */
    @JsonAlias("prop_id")
    private Integer propId;

    /** 房间ID。 */
    @JsonAlias("room_id")
    private Integer roomId;
}
