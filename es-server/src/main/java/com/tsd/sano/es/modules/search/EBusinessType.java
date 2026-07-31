package com.tsd.sano.es.modules.search;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务类型枚举
 * 多语言需要在 后台管理-语言资源-新增对应code作为资源的数据，前端才能显示，比如钱包记录需要正确显示 name 那就需要去新增相关的语言数据
 * <p> 以线上为准，线上数据优先 ，出现问题先补充线上
 *
 * @author: jiangchong
 * @Date: 2025/6/4
 */
@Getter
@AllArgsConstructor
public enum EBusinessType {
    /**
     * 豪华礼物 1-10
     */
    SEND_GIFT("赠送豪华礼物", "Normal gifts", 1, (byte) -1, (byte) 1),
    RECEIVE_GIFT("收到豪华礼物", "Normal gifts", 2, (byte) 1, (byte) 2),
    PROXY_PROFIT("代理豪华礼物分账", "Agency commission", 3, (byte) 1, (byte) 2),
    SON_PROXY_PROFIT("子代理豪华礼物分账", "Agency commission", 4, (byte) 1, (byte) 2),
    ROOM_USER_PROFIT("房主豪华礼物分账", "Agency commission", 5, (byte) 1, (byte) 2),
    /**
     * 幸运礼物 11-20
     */
    SEND_LUCKY_GIFT("赠送幸运礼物", "Lucky Gift", 11, (byte) -1, (byte) 1),//
    RECEIVE_LUCKY_GIFT("收到幸运礼物", "Lucky Gift", 12, (byte) 1, (byte) 2),//
    PROXY_RECEIVE_LUCKY_GIFT("代理幸运礼物分账", "Agency commission", 13, (byte) 1, (byte) 2),//
    SON_PROXY_RECEIVE_LUCKY_GIFT("子代理幸运礼物分账", "Agency commission", 14, (byte) 1, (byte) 2),//
    LUCKY_COIN("幸运礼物中奖", "Lucky Gift", 16, (byte) 1, (byte) 1),
    JACKPOT_LUCKY("幸运礼物jackpot派奖", "Lucky Gift", 17, (byte) 1, (byte) 1),
    /**
     * 游戏 21-30
     */
    GAME_CONSUME("游戏消费", "Game", 21, (byte) -1, (byte) 1),//
    GAME_INCOME("游戏反奖", "Game", 22, (byte) 1, (byte) 1),//
    ANCHOR_GAME_INCOME("房主游戏分账", "Game", 23, (byte) 1, (byte) 2),//
    PROXY_GAME_INCOME("代理游戏分账", "Agency commission", 24, (byte) 1, (byte) 2),//
    SON_PROXY_GAME_INCOME("子代理游戏分账", "Agency commission", 25, (byte) 1, (byte) 2),//
    JACKPOT_GAME("游戏jackpot派奖", "Game", 26, (byte) 1, (byte) 1),//
    /**
     * 交易 31-40
     */
    PAY_COIN_DEDUCT("金币转账扣除", "Transfer", 31, (byte) -1, (byte) 3),//扣除
    PAY_COIN_ADD("转账到金币", "Transfer", 32, (byte) 1, (byte) 1),//增加
    DIAMOND_TO_COIN_DEDUCT("钻石兑换金币", "Exchange", 33, (byte) -1, (byte) 2),//兑换金币扣除钻石
    DIAMOND_TO_COIN_ADD("钻石兑换金币", "Exchange", 34, (byte) 1, (byte) 1),//钻石兑换金币
    DIAMOND_TO_PAY_COIN_DEDUCT("钻石兑换交易金币", "Exchange", 35, (byte) -1, (byte) 2),
    DIAMOND_TO_PAY_COIN_ADD("钻石兑换交易金币", "Exchange", 36, (byte) 1, (byte) 3),
    PAY_COIN_TO_PAY_COIN("转账到交易金币", "Transfer", 37, (byte) 1, (byte) 3),
    /**
     * 其它消费 41-50
     */
    BUY_PROP("购买装扮道具", "Buy Item", 41, (byte) -1, (byte) 1),
    BUY_VIP("购买VIP", "Buy Item", 42, (byte) -1, (byte) 1),//
    SEND_ROOM_RED("直播间发红包", "Red Packets", 43, (byte) -1, (byte) 1),
    RECEIVE_ROOM_RED("直播间收红包", "Red Packets", 44, (byte) 1, (byte) 1),
    BACK_ROOM_RED("直播间红包退回", "Red Packets", 45, (byte) 1, (byte) 1),
    /**
     * 充值提现 51-60
     */
    RECHARGE("金币充值", "Top-up", 51, (byte) 1, (byte) 1),
    PAY_COIN_RECHARGE("交易金币充值", "Top-up", 52, (byte) 1, (byte) 3),
    WITHDRAW("提现扣除", "Withdrawal", 53, (byte) -1, (byte) 2),
    WITHDRAW_RETURN("提现退款", "Withdrawal", 54, (byte) 1, (byte) 2),
    PAY_ROLL_ORDER("提现订单奖励", "Withdrawal", 55, (byte) 1, (byte) 2),

    /**
     * 活动任务 61-500
     */
    ANCHOR_RANK("主播榜奖励", "Platform Rewards", 61, (byte) 1, (byte) 2),
    SIGN_IN_COIN("每日签到奖励", "Platform Rewards", 62, (byte) 1, (byte) 1),
    SIGN_IN_DIAMOND("每日签到奖励", "Platform Rewards", 63, (byte) 1, (byte) 2),
    ACC_SIGN_IN_COIN("累计签到奖励", "Platform Rewards", 64, (byte) 1, (byte) 1),
    ACC_SIGN_IN_DIAMOND("累计签到奖励", "Platform Rewards", 65, (byte) 1, (byte) 2),
    WATCH_TASK_REWARD_COIN("日常观看直播奖励", "Platform Rewards", 66, (byte) 1, (byte) 1),
    WATCH_TASK_REWARD_DIAMOND("日常观看直播奖励", "Platform Rewards", 67, (byte) 1, (byte) 2),
    CARE_TASK_REWARD_COIN("日常关注主播奖励", "Platform Rewards", 68, (byte) 1, (byte) 1),
    CARE_TASK_REWARD_DIAMOND("日常关注主播奖励", "Platform Rewards", 69, (byte) 1, (byte) 2),
    RECHARGE_TASK_REWARD_COIN("日常充值奖励", "Platform Rewards", 70, (byte) 1, (byte) 1),//奖励金币
    RECHARGE_TASK_REWARD_DIAMOND("日常充值奖励", "Platform Rewards", 71, (byte) 1, (byte) 2),
    CONSUME_TASK_REWARD_COIN("日常榜单奖励", "Platform Rewards", 72, (byte) 1, (byte) 1),//奖励金币
    CONSUME_TASK_REWARD_DIAMOND("日常榜单奖励", "Platform Rewards", 73, (byte) 1, (byte) 2),
    LIVE_HOUR_REWARD("直播时长奖励", "Platform Rewards", 74, (byte) 1, (byte) 2),
    LUCKY_INCOME_REWARD("幸运礼物收益奖励", "Platform Rewards", 75, (byte) 1, (byte) 2),
    TASK_SEND_LUCKY_GIFT_COIN("日常幸运礼物消耗奖励", "Platform Rewards", 76, (byte) 1, (byte) 1),
    TASK_SEND_LUCKY_GIFT_DIAMOND("日常幸运礼物消耗奖励", "Platform Rewards", 77, (byte) 1, (byte) 2),
    TASK_SEND_GAME_CONSUME_COIN("日常游戏消耗奖励", "Platform Rewards", 78, (byte) 1, (byte) 1),
    TASK_SEND_GAME_CONSUME_DIAMOND("日常游戏消耗奖励", "Platform Rewards", 79, (byte) 1, (byte) 2),
    TASK_SEND_DYNAMICS_COIN("日常发布动态奖励", "Platform Rewards", 80, (byte) 1, (byte) 1),
    TASK_SEND_DYNAMICS_DIAMOND("日常发布动态奖励", "Platform Rewards", 81, (byte) 1, (byte) 2),
    NEW_PROXY_INVITE_COIN("新代理邀请奖励", "Platform Rewards", 82, (byte) 1, (byte) 1),
    NEW_PROXY_INVITE_DIAMOND("新代理邀请奖励", "Platform Rewards", 83, (byte) 1, (byte) 2),
    NEW_PROXY_INCOME_COIN("新代理累计流水奖励", "Platform Rewards", 84, (byte) 1, (byte) 1),
    NEW_PROXY_INCOME_DIAMOND("新代理累计流水奖励", "Platform Rewards", 85, (byte) 1, (byte) 2),
    INVITE_RAFFLE_COIN("邀请抽奖", "Platform Rewards", 86, (byte) 1, (byte) 1),
    INVITE_RAFFLE_DIAMOND("邀请抽奖", "Platform Rewards", 87, (byte) 1, (byte) 2),
    VIP_DAY_REWARD("vip日奖励", "Platform Rewards", 88, (byte) 1, (byte) 1),
    DAY_SHANG_MAI_COIN("日常上麦奖励", "Platform Rewards", 89, (byte) 1, (byte) 1),
    DAY_SHANG_MAI_DIAMOND("日常上麦奖励", "Platform Rewards", 90, (byte) 1, (byte) 2),
    ANCHOR_QUALITY_REWARD("优质主播时长奖励", "Platform Rewards", 91, (byte) 1, (byte) 2),
    INVITE_RANK("邀请榜单奖励", "Platform Rewards", 92, (byte) 1, (byte) 1),
    SUPPER_HOST_PROXY_INCOME("代理超级主播收益奖励", "Platform Rewards", 93, (byte) 1, (byte) 2),
    SUPPER_HOST_PROXY_INCOME_LUCKY("代理超级主播幸运礼物收益奖励", "Platform Rewards", 94, (byte) 1, (byte) 2),
    SUPPER_HOST_INCOME("超级主播收益奖励", "Platform Rewards", 95, (byte) 1, (byte) 2),
    SUPPER_HOST_INCOME_LUCKY("超级主播幸运礼物收益奖励", "Platform Rewards", 96, (byte) 1, (byte) 2),
    ACT_RANK_LUCKY_CONSUME("幸运礼物消费榜单奖励", "Platform Rewards", 97, (byte) 1, (byte) 1),
    ACT_RANK_LUCKY_INCOME("幸运礼物收入榜单奖励", "Platform Rewards", 98, (byte) 1, (byte) 2),
    ACT_RANK_GAME_CONSUME("游戏消费榜单奖励", "Platform Rewards", 99, (byte) 1, (byte) 1),
    ACT_RANK_GAME_INCOME("游戏收入榜单奖励", "Platform Rewards", 100, (byte) 1, (byte) 2),
    ACT_RANK_PROXY_INCOME("代理收入榜单奖励", "Platform Rewards", 101, (byte) 1, (byte) 2),
    BALL_BET_COST("世界杯竞猜投注", "World Cup", 102, (byte) -1, (byte) 1),
    BALL_BET_REWARD("世界杯竞猜奖励", "World Cup", 103, (byte) 1, (byte) 1),
    ROOM_POOL_REWARD("房间奖池奖励", "Lucky Drop Activated", 104, (byte) 1, (byte) 1),
    VIP_PLAN_REPLACEMENT_CARD("购买VIP套餐补签卡", "Buy vip resign", 105, (byte) -1, (byte) 1),
    VIP_PLAN_BUY("购买VIP套餐", "Buy vip", 106, (byte) -1, (byte) 1),
    VIP_PLAN_DAY_REWARD("VIP签到奖励", "vip sign in", 107, (byte) 1, (byte) 1),
    VIP_PLAN_TASK_REWARD("VIP任务奖励", "vip task", 108, (byte) 1, (byte) 1),
    HIGH_USER_REWARD("优质主播奖励", "Platform Rewards", 109, (byte) 1, (byte) 2),
    PK_RANK_TOP_REWARD("PK榜单奖励", "Platform Rewards", 110, (byte) 1, (byte) 2),
    /**
     * 后台调整 501-550
     */
    ADMIN_ADJUST_COIN("运营调整", "Platform Adjustment", 501, (byte) 1, (byte) 1),
    ADMIN_ADJUST_COIN_MINUS("运营调整", "Platform Adjustment", 502, (byte) -1, (byte) 1),
    ADMIN_ADJUST_DIAMOND("运营调整", "Platform Adjustment", 503, (byte) 1, (byte) 2),
    ADMIN_ADJUST_DIAMOND_MINUS("运营调整", "Platform Adjustment", 504, (byte) -1, (byte) 2),
    ADMIN_ADJUST_PAY_COIN("运营调整", "Platform Adjustment", 505, (byte) 1, (byte) 3),
    ADMIN_ADJUST_PAY_COIN_MINUS("运营调整", "Platform Adjustment", 506, (byte) -1, (byte) 3),
    ;
    private final String name;
    private final String title;
    private final Integer code;
    //钱包变化方式，1加余额，-1 扣除余额
    private final byte variation;
    //类型：1 金币，2 钻石，3 交易金币
    private final Byte moneyType;

}
