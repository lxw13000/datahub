package com.tsd.sano.es.controller.diamond;

import com.tsd.sano.es.controller.diamond.dto.AppDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.dto.DiamondIncomeDistributionDTO;
import com.tsd.sano.es.controller.diamond.dto.SatDiamond7DayDTO;
import com.tsd.sano.es.controller.diamond.dto.SearchDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.dto.WithdrawDiamondAnalysisDTO;
import com.tsd.sano.es.controller.diamond.vo.DiamondIncomeRangeVO;
import com.tsd.sano.es.controller.diamond.vo.DiamondRecordVO;
import com.tsd.sano.es.controller.diamond.vo.WithdrawDiamondAnalysisVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.modules.search.service.WalletDiamondRecordSearch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 钻石记录接口
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/2 21:55
 */
@RestController
@RequestMapping("/walletDiamond")
@RequiredArgsConstructor
@Slf4j
public class WalletDiamondController {


    /**
     * 钻石记录 ES 查询服务
     */
    private final WalletDiamondRecordSearch walletDiamondRecordSearch;


    /**
     * 查询 App 个人钻石记录
     *
     * @param recordDTO App 查询参数
     * @return 钻石流水记录列表
     */
    @PostMapping("/app/diamondRecords")
    public ResultVO<List<DiamondRecordVO>> searchDiamondRecords(@RequestBody AppDiamondRecordDTO recordDTO) {

        // App 查询固定使用 search_after 深度分页，保证大数据量下翻页稳定
        SearchDiamondRecordDTO dto = new SearchDiamondRecordDTO();
        dto.setSearchType(0);
        dto.setUserId(recordDTO.getUserId());
        dto.setBusinessType(recordDTO.getBusinessType());
        dto.setStartTime(recordDTO.getStartTime());
        dto.setEndTime(recordDTO.getEndTime());
        dto.setPageSize(recordDTO.getPageSize());
        dto.setLastCreateTime(recordDTO.getLastCreateTime());
        dto.setLastId(recordDTO.getLastId());
        List<DiamondRecordVO> records = walletDiamondRecordSearch.list(dto);
        return ResultVO.success(records);
    }

    /**
     * 查询钻石流水记录总数
     *
     * @param recordDTO 查询参数
     * @return 符合条件的记录总数
     */
    @PostMapping("/count")
    public ResultVO<Long> count(@RequestBody SearchDiamondRecordDTO recordDTO) {
        long count = walletDiamondRecordSearch.count(recordDTO);
        return ResultVO.success(count);
    }

    /**
     * 查询钻石流水记录列表
     *
     * @param recordDTO 查询参数
     * @return 钻石流水记录列表
     */
    @PostMapping("/list")
    public ResultVO<List<DiamondRecordVO>> list(@RequestBody SearchDiamondRecordDTO recordDTO) {
        List<DiamondRecordVO> records = walletDiamondRecordSearch.list(recordDTO);
        return ResultVO.success(records);
    }


    /**
     * 近7天直播收入
     *
     * @param dto 参数
     * @return com.tsd.sano.es.core.result.ResultVO<java.lang.Long>
     * @author lxw
     * @date 2026/7/14 20:04
     **/
    @PostMapping("/sevenDaysLiveIncome")
    public ResultVO<Long> sevenDaysLiveIncome(@RequestBody SatDiamond7DayDTO dto) {
        Long count = walletDiamondRecordSearch.sevenDaysLiveIncome(dto);
        return ResultVO.success(count);
    }

    /**
     * 按用户注册时间查询钻石收入分布。
     *
     * @param dto 用户注册时间范围
     * @return 各钻石收入区间的用户数量
     */
    @PostMapping("/incomeDistribution")
    public ResultVO<List<DiamondIncomeRangeVO>> incomeDistribution(
            @RequestBody DiamondIncomeDistributionDTO dto) {
        return ResultVO.success(walletDiamondRecordSearch.incomeDistribution(dto));
    }

    /**
     * 统计指定提现用户在独立时间范围内的钻石来源和用途。
     *
     * @param dto 提现用户筛选时间和钻石统计时间
     * @return 用户、余额变化方向和业务类型组成的扁平汇总明细
     */
    @PostMapping("/withdrawAnalysis")
    public ResultVO<List<WithdrawDiamondAnalysisVO>> withdrawAnalysis(
            @RequestBody WithdrawDiamondAnalysisDTO dto) {
        return ResultVO.success(walletDiamondRecordSearch.withdrawAnalysis(dto));
    }

}
