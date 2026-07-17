package com.tsd.sano.es.controller.coin;

import com.tsd.sano.es.controller.coin.dto.AppCoinRecordDTO;
import com.tsd.sano.es.controller.coin.dto.CoinGiftDailyStatDTO;
import com.tsd.sano.es.controller.coin.dto.SatCoinWeekDTO;
import com.tsd.sano.es.controller.coin.dto.SearchCoinRecordDTO;
import com.tsd.sano.es.controller.coin.vo.CoinGiftDailyPropStatVO;
import com.tsd.sano.es.controller.coin.vo.CoinGiftDailyStatVO;
import com.tsd.sano.es.controller.coin.vo.CoinRecordVO;
import com.tsd.sano.es.controller.coin.vo.WeekStatVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.search.WalletCoinRecordSearch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 金币记录接口
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/2 21:55
 */
@RestController
@RequestMapping("/walletCoin")
@RequiredArgsConstructor
@Slf4j
public class WalletCoinController {


    private final WalletCoinRecordSearch walletCoinRecordSearch;


    /**
     * 统计指定房间金币周数据
     */
    @PostMapping("/staCoinWeek")
    public ResultVO<WeekStatVO> staCoinWeek(@RequestBody SatCoinWeekDTO weekDTO) {
        long startMillis = System.currentTimeMillis();
        // 查询API入口日志，便于和ES查询层日志串联排查。
        log.info("===> ES-Search api start. api=staCoinWeek, roomIds={}, startTime={}, endTime={}",
                weekDTO.getRoomIds(), weekDTO.getStartTime(), weekDTO.getEndTime());

        WeekStatVO stat = walletCoinRecordSearch.staWeek(weekDTO.getRoomIds(), weekDTO.getStartTime(), weekDTO.getEndTime());
        log.info("===> ES-Search api success. api=staCoinWeek, roomCount={}, startTime={}, endTime={}, costMs={}, stat={}",
                weekDTO.getRoomIds().size(), weekDTO.getStartTime(), weekDTO.getEndTime(), System.currentTimeMillis() - startMillis, stat);
        return ResultVO.success(stat);
    }

    /**
     * 按业务日期统计金币礼物消费、返奖及每日消费 Top3 礼物。
     *
     * <p>消费统计使用幸运礼物消费，返奖和 Jackpot 中奖分别统计。</p>
     *
     * @param statDTO 查询日期范围
     * @return 每日统计结果
     */
    @PostMapping("/coinGiftDailyStat")
    public ResultVO<List<CoinGiftDailyStatVO>> coinGiftDailyStat(@RequestBody CoinGiftDailyStatDTO statDTO) {
        long startMillis = System.currentTimeMillis();
        log.info("===> ES-Search api start. api=coinGiftDailyStat, startDate={}, endDate={}",
                statDTO.getStartDate(), statDTO.getEndDate());

        List<CoinGiftDailyStatVO> stats = walletCoinRecordSearch.coinGiftDailyStat(
                statDTO.getStartDate(), statDTO.getEndDate());
        log.info("===> ES-Search api success. api=coinGiftDailyStat, startDate={}, endDate={}, size={}, costMs={}",
                statDTO.getStartDate(), statDTO.getEndDate(), stats.size(), System.currentTimeMillis() - startMillis);
        return ResultVO.success(stats);
    }

    /**
     * 按天统计每个幸运礼物的消费金额，每天最多返回消费金额最高的 20 个礼物。
     *
     * @param statDTO 查询日期范围
     * @return 按日期分组的幸运礼物消费金额
     */
    @PostMapping("/coinGiftDailyPropStat")
    public ResultVO<List<CoinGiftDailyPropStatVO>> coinGiftDailyPropStat(@RequestBody CoinGiftDailyStatDTO statDTO) {
        long startMillis = System.currentTimeMillis();
        log.info("===> ES-Search api start. api=coinGiftDailyPropStat, startDate={}, endDate={}",
                statDTO.getStartDate(), statDTO.getEndDate());

        List<CoinGiftDailyPropStatVO> stats = walletCoinRecordSearch.coinGiftDailyPropStat(
                statDTO.getStartDate(), statDTO.getEndDate());
        log.info("===> ES-Search api success. api=coinGiftDailyPropStat, startDate={}, endDate={}, size={}, costMs={}",
                statDTO.getStartDate(), statDTO.getEndDate(), stats.size(), System.currentTimeMillis() - startMillis);
        return ResultVO.success(stats);
    }

    /**
     * 查询app个人金币记录
     */
    @PostMapping("/searchCoinRecords")
    public ResultVO<List<CoinRecordVO>> searchCoinRecords(@RequestBody AppCoinRecordDTO recordDTO) {

        SearchCoinRecordDTO dto = new SearchCoinRecordDTO();
        dto.setSearchType(0);
        dto.setUserId(recordDTO.getUserId());
        dto.setBusinessType(recordDTO.getBusinessType());
        dto.setStartTime(recordDTO.getStartTime());
        dto.setEndTime(recordDTO.getEndTime());
        dto.setPageSize(recordDTO.getPageSize());
        dto.setLastCreateTime(recordDTO.getLastCreateTime());
        dto.setLastId(recordDTO.getLastId());
        List<CoinRecordVO> coinRecordVOS = walletCoinRecordSearch.list(dto);
        return ResultVO.success(coinRecordVOS);
    }

    /**
     * 查询金币流水记录总数
     */
    @PostMapping("/count")
    public ResultVO<Long> count(@RequestBody SearchCoinRecordDTO recordDTO) {
        long count = walletCoinRecordSearch.count(recordDTO);
        return ResultVO.success(count);
    }

    /**
     * 查询金币流水记录列表
     */
    @PostMapping("/list")
    public ResultVO<List<CoinRecordVO>> list(@RequestBody SearchCoinRecordDTO recordDTO) {
        List<CoinRecordVO> coinRecordVOS = walletCoinRecordSearch.list(recordDTO);
        return ResultVO.success(coinRecordVOS);
    }
}
