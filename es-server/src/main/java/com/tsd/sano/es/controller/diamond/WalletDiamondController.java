package com.tsd.sano.es.controller.diamond;

import com.tsd.sano.es.controller.diamond.dto.AppDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.dto.SearchDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.vo.DiamondRecordVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.search.WalletDiamondRecordSearch;
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
}
