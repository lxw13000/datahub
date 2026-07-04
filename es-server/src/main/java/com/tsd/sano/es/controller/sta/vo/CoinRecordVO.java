package com.tsd.sano.es.controller.sta.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.tsd.sano.es.core.util.EsLocalDateTimeDeserializer;
import com.tsd.sano.es.core.util.SpaceLocalDateTimeSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 金币流水记录VO。
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/3 00:20
 */
@Data
public class CoinRecordVO {

    /**
     * 流水ID。
     */
    private Long id;

    /**
     * 公共ID。
     */
    @JsonAlias("common_id")
    private Long commonId;

    /**
     * 用户ID。
     */
    @JsonAlias("user_id")
    private Long userId;

    /**
     * 创建时间，读取ES时兼容T格式，接口返回空格分隔格式。
     */
    @JsonAlias("create_time")
    @JsonDeserialize(using = EsLocalDateTimeDeserializer.class)
    @JsonSerialize(using = SpaceLocalDateTimeSerializer.class)
    private LocalDateTime createTime;

    /**
     * 日期。
     */
    private String dt;

    /**
     * 正负状态。
     */
    private Integer status;

    /**
     * 金币数量。
     */
    private Long tokens;

    /**
     * 变化后金币。
     */
    @JsonAlias("tokens_new")
    private Long tokensNew;

    /**
     * 变化前金币。
     */
    @JsonAlias("tokens_old")
    private Long tokensOld;

    /**
     * 业务类型。
     */
    @JsonAlias("business_type")
    private Integer businessType;

    /**
     * 数量。
     */
    private Integer amount;

    /**
     * 礼物ID。
     */
    @JsonAlias("prop_id")
    private Integer propId;

    /**
     * 房间ID。
     */
    @JsonAlias("room_id")
    private Integer roomId;

    /**
     * 对方用户ID。
     */
    @JsonAlias("ta_user_id")
    private Integer taUserId;

    /**
     * 性别。
     */
    private Integer sex;

    /**
     * 国家代码。
     */
    @JsonAlias("country_code")
    private String countryCode;

    /**
     * 是否主播。
     */
    private Integer anchor;

    /**
     * 分钟桶。
     */
    @JsonAlias("bucket_1m")
    @JsonDeserialize(using = EsLocalDateTimeDeserializer.class)
    @JsonSerialize(using = SpaceLocalDateTimeSerializer.class)
    private LocalDateTime bucket1m;
}
