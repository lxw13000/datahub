package com.tsd.sano.es.controller.diamond.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.tsd.sano.es.core.util.EsLocalDateTimeDeserializer;
import com.tsd.sano.es.core.util.SpaceLocalDateTimeSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钻石流水记录结果
 *
 * @author lxw
 */
@Data
public class DiamondRecordVO {

    /**
     * 流水 ID
     */
    private Long id;

    /**
     * 公共 ID
     */
    @JsonAlias("common_id")
    private Long commonId;

    /**
     * 用户 ID
     */
    @JsonAlias("user_id")
    private Long userId;

    /**
     * 创建时间，读取 ES 时兼容 T 格式，接口返回空格分隔格式
     */
    @JsonAlias("create_time")
    @JsonDeserialize(using = EsLocalDateTimeDeserializer.class)
    @JsonSerialize(using = SpaceLocalDateTimeSerializer.class)
    private LocalDateTime createTime;

    /**
     * 业务日期
     */
    private String dt;

    /**
     * 正负状态
     */
    private Integer status;

    /**
     * 钻石数量
     */
    private Long tokens;

    /**
     * 变化后钻石数量
     */
    @JsonAlias("tokens_new")
    private Long tokensNew;

    /**
     * 变化前钻石数量
     */
    @JsonAlias("tokens_old")
    private Long tokensOld;

    /**
     * 业务类型
     */
    @JsonAlias("business_type")
    private Integer businessType;

    /**
     * 数量
     */
    private Integer amount;

    /**
     * 礼物 ID
     */
    @JsonAlias("prop_id")
    private Integer propId;

    /**
     * 房间 ID
     */
    @JsonAlias("room_id")
    private Integer roomId;

    /**
     * 对方用户 ID
     */
    @JsonAlias("ta_user_id")
    private Integer taUserId;

    /**
     * 性别
     */
    private Integer sex;

    /**
     * 国家代码
     */
    @JsonAlias("country_code")
    private String countryCode;

    /**
     * 是否主播
     */
    private Integer anchor;

    /**
     * 代理上账钻石数量
     */
    @JsonAlias("proxy_tokens")
    private Long proxyTokens;

    /**
     * 对方流水钻石数量
     */
    @JsonAlias("ta_tokens")
    private Long taTokens;

    /**
     * 分钟桶
     */
    @JsonAlias("bucket_1m")
    @JsonDeserialize(using = EsLocalDateTimeDeserializer.class)
    @JsonSerialize(using = SpaceLocalDateTimeSerializer.class)
    private LocalDateTime bucket1m;
}
