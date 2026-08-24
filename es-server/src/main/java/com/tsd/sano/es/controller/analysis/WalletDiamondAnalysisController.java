package com.tsd.sano.es.controller.analysis;

import com.tsd.sano.es.controller.analysis.dto.RoomWalletAnalysisDTO;
import com.tsd.sano.es.controller.analysis.dto.UserDiamondAnalysisDTO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomePropTopVO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomeSourceTopVO;
import com.tsd.sano.es.controller.analysis.vo.RoomDiamondDailyIncomeStatVO;
import com.tsd.sano.es.controller.analysis.vo.UserDiamondDailyStatVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.modules.search.service.WalletDiamondAnalysisSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户钻石收入分析接口。
 *
 * @author lxw
 */
@RestController
@RequestMapping("/walletDiamondAnalysis")
@RequiredArgsConstructor
public class WalletDiamondAnalysisController {

    /**
     * 用户钻石收入分析ES查询服务。
     */
    private final WalletDiamondAnalysisSearch walletDiamondAnalysisSearch;

    /**
     * 按天统计指定用户的普通礼物、幸运礼物和游戏钻石收入。
     *
     * @param dto 用户及业务日期范围
     * @return 日期范围内连续的每日统计，没有收入的日期返回全0数据
     */
    @PostMapping("/dailyStat")
    public ResultVO<List<UserDiamondDailyStatVO>> dailyStat(@RequestBody UserDiamondAnalysisDTO dto) {
        return ResultVO.success(walletDiamondAnalysisSearch.dailyStat(dto));
    }

    /**
     * 按天统计指定房间集合的普通礼物、幸运礼物和游戏钻石收入。
     *
     * @param dto 房间集合及业务日期范围
     * @return 合并所有指定房间后的连续每日收入统计
     */
    @PostMapping("/roomDailyIncomeStat")
    public ResultVO<List<RoomDiamondDailyIncomeStatVO>> roomDailyIncomeStat(@RequestBody RoomWalletAnalysisDTO dto) {
        return ResultVO.success(walletDiamondAnalysisSearch.roomDailyIncomeStat(dto));
    }

    /**
     * 按累计收入tokens统计指定用户的普通礼物、幸运礼物和游戏收入来源用户Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类钻石收入对应的ta_user_id Top10
     */
    @PostMapping("/incomeSourceTop10")
    public ResultVO<DiamondIncomeSourceTopVO> incomeSourceTop10(@RequestBody UserDiamondAnalysisDTO dto) {
        return ResultVO.success(walletDiamondAnalysisSearch.incomeSourceTop10(dto));
    }

    /**
     * 按累计收入tokens统计指定用户的普通礼物、幸运礼物和游戏具体收入类型Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类钻石收入对应的prop_id Top10
     */
    @PostMapping("/incomePropTop10")
    public ResultVO<DiamondIncomePropTopVO> incomePropTop10(@RequestBody UserDiamondAnalysisDTO dto) {
        return ResultVO.success(walletDiamondAnalysisSearch.incomePropTop10(dto));
    }
}
