package com.tsd.sano.es.controller.diamondfreeze;

import com.tsd.sano.es.controller.diamondfreeze.dto.SearchDiamondFreezeRecordDTO;
import com.tsd.sano.es.controller.diamondfreeze.vo.DiamondFreezeRecordVO;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.modules.search.service.WalletDiamondFreezeRecordSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 钻石冻结记录查询接口。
 *
 * <p>冻结记录使用独立索引和字段模型，不与普通钻石流水查询混用。</p>
 */
@RestController
@RequestMapping("/walletDiamondFreeze")
@RequiredArgsConstructor
public class WalletDiamondFreezeController {

    /** 钻石冻结记录ES查询服务。 */
    private final WalletDiamondFreezeRecordSearch walletDiamondFreezeRecordSearch;

    /**
     * 查询钻石冻结记录总数。
     *
     * @param recordDTO 查询条件
     * @return 符合条件的记录总数
     */
    @PostMapping("/count")
    public ResultVO<Long> count(@RequestBody SearchDiamondFreezeRecordDTO recordDTO) {
        return ResultVO.success(walletDiamondFreezeRecordSearch.count(recordDTO));
    }

    /**
     * 分页查询钻石冻结记录。
     *
     * @param recordDTO 查询和分页条件
     * @return 钻石冻结记录列表
     */
    @PostMapping("/list")
    public ResultVO<List<DiamondFreezeRecordVO>> list(@RequestBody SearchDiamondFreezeRecordDTO recordDTO) {
        return ResultVO.success(walletDiamondFreezeRecordSearch.list(recordDTO));
    }
}
