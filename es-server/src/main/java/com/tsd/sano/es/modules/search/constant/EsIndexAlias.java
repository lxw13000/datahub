package com.tsd.sano.es.modules.search.constant;

/**
 * 已配置同步的业务表查询Alias，与YAML中的index-alias保持一致。
 *
 * <p>这里只定义业务别名，不包含日/月/年后缀或SINGLE物理索引版本号。</p>
 *
 * @author lxw
 * @date 2021-06-09 10:59
 */
public interface EsIndexAlias {

    /**
     * 金币记录
     */
    String SANO_WALLET_COIN_RECORD = "sano_wallet_coin_record";

    /**
     * 钻石记录
     */
    String SANO_WALLET_DIAMOND_RECORD = "sano_wallet_diamond_record";

    /**
     * 钻石冻结记录明细
     */
    String SANO_WALLET_DIAMOND_FREEZE_RECORD = "sano_wallet_diamond_freeze_record";


    /**
     * 钻石记录10分钟记录明细
     */
    String SANO_WALLET_LUCKY_DIAMOND_RECORD_10M = "sano_wallet_lucky_diamond_record_10m";


    /**
     * 游戏记录
     */
    String SANO_GAME_RECORD = "sano_game_record";

    /**
     * 充值订单，指向SINGLE固定物理索引。
     */
    String SANO_WALLET_RECHARGE = "sano_wallet_recharge";

    /**
     * 主播日数据，聚合MONTH月物理索引。
     */
    String SANO_ANCHOR_DATA = "sano_anchor_data";

    /**
     * 代理旗下主播日数据，聚合MONTH月物理索引。
     */
    String SANO_PROXY_ANCHOR_DATA = "sano_proxy_anchor_data";

}
