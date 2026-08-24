package com.tsd.sano.es.controller.analysis;

import com.tsd.sano.es.controller.analysis.dto.RoomWalletAnalysisDTO;
import com.tsd.sano.es.controller.analysis.dto.UserCoinAnalysisDTO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumePropTopVO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumeTargetTopVO;
import com.tsd.sano.es.controller.analysis.vo.RoomCoinDailyConsumeStatVO;
import com.tsd.sano.es.controller.analysis.vo.UserCoinDailyStatVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.modules.search.service.WalletCoinAnalysisSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户金币消费和返奖分析接口。
 *
 * @author lxw
 */
@RestController
@RequestMapping("/walletCoinAnalysis")
@RequiredArgsConstructor
public class WalletCoinAnalysisController {

    /**
     * 用户金币分析ES查询服务。
     */
    private final WalletCoinAnalysisSearch walletCoinAnalysisSearch;

    /**
     * 按天统计指定用户的普通礼物、幸运礼物、游戏、商城道具和VIP消费，
     * 以及幸运礼物返奖、游戏返奖和两类Jackpot中奖数据。
     *
     * @param dto 用户及业务日期范围
     * @return 日期范围内连续的每日统计，没有流水的日期返回全0数据
     */
    @PostMapping("/dailyStat")
    public ResultVO<List<UserCoinDailyStatVO>> dailyStat(@RequestBody UserCoinAnalysisDTO dto) {
        return ResultVO.success(walletCoinAnalysisSearch.dailyStat(dto));
    }

    /**
     * 按天统计指定房间集合的普通礼物、幸运礼物和游戏金币消费。
     *
     * @param dto 房间集合及业务日期范围
     * @return 合并所有指定房间后的连续每日消费统计
     */
    @PostMapping("/roomDailyConsumeStat")
    public ResultVO<List<RoomCoinDailyConsumeStatVO>> roomDailyConsumeStat(@RequestBody RoomWalletAnalysisDTO dto) {
        return ResultVO.success(walletCoinAnalysisSearch.roomDailyConsumeStat(dto));
    }

    /**
     * 按累计消费tokens统计指定用户的普通礼物、幸运礼物和游戏消费去处Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类消费对应的ta_user_id Top10
     */
    @PostMapping("/consumeTargetTop10")
    public ResultVO<CoinConsumeTargetTopVO> consumeTargetTop10(@RequestBody UserCoinAnalysisDTO dto) {
        return ResultVO.success(walletCoinAnalysisSearch.consumeTargetTop10(dto));
    }

    /**
     * 按累计消费tokens统计指定用户的普通礼物、幸运礼物和游戏具体消费类型Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类消费对应的prop_id Top10
     */
    @PostMapping("/consumePropTop10")
    public ResultVO<CoinConsumePropTopVO> consumePropTop10(@RequestBody UserCoinAnalysisDTO dto) {
        return ResultVO.success(walletCoinAnalysisSearch.consumePropTop10(dto));
    }
}
