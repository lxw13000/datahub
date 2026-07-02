package com.tsd.sano.es.controller.sta;

import com.tsd.sano.es.controller.sta.dto.SatCoinWeekDTO;
import com.tsd.sano.es.controller.sta.vo.WeekStatVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.search.WalletCoinRecordSearch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeParseException;

/**
 * 钱包金币统计接口。
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
     * 统计指定房间在指定时间段内的金币周数据。
     */
    @PostMapping("/staCoinWeek")
    public ResultVO<WeekStatVO> staCoinWeek(@RequestBody SatCoinWeekDTO weekDTO) {
        long startMillis = System.currentTimeMillis();
        try {
            // 查询API入口日志，便于和ES查询层日志串联排查。
            log.info("===> ES-Search api start. api=staCoinWeek, roomIds={}, startTime={}, endTime={}",
                    weekDTO.getRoomIds(), weekDTO.getStartTime(), weekDTO.getEndTime());

            WeekStatVO stat = walletCoinRecordSearch.staWeek(weekDTO.getRoomIds(), weekDTO.getStartTime(), weekDTO.getEndTime());
            log.info("===> ES-Search api success. api=staCoinWeek, roomCount={}, startTime={}, endTime={}, costMs={}, stat={}",
                    weekDTO.getRoomIds().size(), weekDTO.getStartTime(), weekDTO.getEndTime(), System.currentTimeMillis() - startMillis, stat);
            return ResultVO.success(stat);
        } catch (DateTimeParseException e) {
            log.error("===> ES-Search api date parse failed. api=staCoinWeek, startTime={}, endTime={}, costMs={}, error={}",
                    weekDTO.getStartTime(), weekDTO.getEndTime(), System.currentTimeMillis() - startMillis, e.getMessage(), e);
            return ResultVO.success(new WeekStatVO());
        }
    }
}
