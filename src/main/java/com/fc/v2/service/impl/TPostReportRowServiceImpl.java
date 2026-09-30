package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TPostReportRowMapper;
import com.fc.v2.model.auto.TPostReportRow;
import com.fc.v2.service.ITPostReportRowService;

/**
 * 月度业务报表核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostReportRowServiceImpl implements ITPostReportRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TPostReportRowMapper postReportRowMapper;

    @Override
    public TPostReportRow selectTPostReportRowById(Long id) {
        return this.postReportRowMapper.selectById(id);
    }

    /**
     * 整批提交。口径只有一处：
     * 1) 空批次（null/无行）直接返回 0，不碰库，杜绝 rows.get(0) 越界；
     * 2) 行数超 {@link #MAX_ROWS} 返回 -1，整批不入库（先于任何写操作判定）；
     * 3) 同一 batch_no 已核收过即整批幂等，返回已收成功行数，重复提交不再产生行；
     * 4) 逐行校验、逐行落库——合法行收（status=1），非法/批内明细码重复行挂起（status=2），
     *    失败行不再连累合法行；失败明细保留**原报表行次**（行内自带 rowNo，缺省取提交序号）；
     * 5) 成功行数即实际入库的合法行数，返回值与库内口径同源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int submitBatch(String batchNo, List<TPostReportRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }

        // 整批幂等：该批次只要落过行（无论已收/挂起），重复提交不再产生任何行，
        // 返回上一次的成功行数，保证"同批交两回，数量不翻倍"。
        Integer existed = this.postReportRowMapper.selectCount(new QueryWrapper<TPostReportRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", 0));
        if (existed != null && existed > 0) {
            Integer accepted = this.postReportRowMapper.selectCount(new QueryWrapper<TPostReportRow>()
                    .eq("batch_no", batchNo)
                    .eq("del_flag", 0)
                    .eq("status", STATUS_OK));
            return accepted == null ? 0 : accepted;
        }

        Set<String> seenItemCodes = new HashSet<String>();
        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            TPostReportRow r = rows.get(i);
            // 原行号：行内自带优先，缺省回退提交序号（从 1 起），失败行按原报表行次回查
            if (r.getRowNo() == null) {
                r.setRowNo(Integer.valueOf(i + 1));
            }
            r.setBatchNo(batchNo);
            r.setDelFlag(0);

            String itemCode = r.getItemCode() == null ? null : r.getItemCode().trim();
            boolean valid = itemCode != null && !itemCode.isEmpty()
                    && r.getQty() != null
                    && r.getQty().compareTo(java.math.BigDecimal.ZERO) > 0;
            boolean duplicateInBatch = itemCode != null && !seenItemCodes.add(itemCode);

            if (valid && !duplicateInBatch) {
                r.setItemCode(itemCode);
                r.setStatus(STATUS_OK);
                this.postReportRowMapper.insert(r);
                ok++;
            } else {
                r.setStatus(STATUS_FAIL);
                if (r.getRemark() == null || r.getRemark().trim().isEmpty()) {
                    r.setRemark(duplicateInBatch ? "批内明细码重复" : "明细码或业务量不合法");
                }
                this.postReportRowMapper.insert(r);
            }
        }
        return ok;
    }

    @Override
    public List<TPostReportRow> listErrors(String batchNo) {
        List<TPostReportRow> list = this.postReportRowMapper.selectList(new QueryWrapper<TPostReportRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", 0)
                .eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
        return list == null ? new ArrayList<TPostReportRow>() : list;
    }
}
