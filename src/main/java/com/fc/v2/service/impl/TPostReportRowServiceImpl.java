package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * 行落地情形与 t_post_report_row.status 注释同源：0待核 1已收 2挂起（失败明细）。
 * 批次幂等键为 (batch_no, row_no)：重复提交只更新不新增，数量不会凭空翻倍。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostReportRowServiceImpl implements ITPostReportRowService {

    /** 单批行数上限，超出整批拒收 */
    private static final int MAX_ROWS = 500;

    /** 行落地情形 0待核 1已收 2挂起（与表 status 注释同源，禁止另立一套数字） */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_OK = 1;
    private static final int STATUS_HANG = 2;

    private static final int DEL_NORMAL = 0;

    @javax.annotation.Resource
    private TPostReportRowMapper postReportRowMapper;

    @Override
    public TPostReportRow selectTPostReportRowById(Long id) {
        return this.postReportRowMapper.selectById(id);
    }

    /**
     * 整批提交，一个事务内完成：合法行置“已收”、非法/批内重复行置“挂起”并保留原行号，
     * 任一步异常整体回滚，避免成功行与失败明细两边状态对不齐。
     *
     * 返回成功（已收）行数；空批返回 0；超过 {@link #MAX_ROWS} 返回 -1 且整批不入库。
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

        // 该批次已落库的行，按原行号索引：同批重交只更新，绝不重复插入
        Map<Integer, TPostReportRow> existed = new HashMap<Integer, TPostReportRow>();
        List<TPostReportRow> oldRows = this.postReportRowMapper.selectList(new QueryWrapper<TPostReportRow>()
                .eq("batch_no", batchNo).eq("del_flag", DEL_NORMAL));
        for (TPostReportRow old : oldRows) {
            if (old.getRowNo() != null) {
                existed.put(old.getRowNo(), old);
            }
        }

        Set<String> seenItemCodes = new HashSet<String>();
        int seq = 0;
        int ok = 0;
        for (TPostReportRow r : rows) {
            seq++;
            r.setBatchNo(batchNo);
            // 原行号必须随行走；报送方未给行次时才用批内序号兜底，失败行才找得到位置
            if (r.getRowNo() == null) {
                r.setRowNo(Integer.valueOf(seq));
            }
            r.setDelFlag(DEL_NORMAL);

            String code = r.getItemCode() == null ? null : r.getItemCode().trim();
            boolean codeInvalid = code == null || code.isEmpty();
            boolean qtyInvalid = r.getQty() == null
                    || r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0;
            // 同一批里同一条明细码只能进一回，后出现的行挂起
            boolean duplicateInBatch = !codeInvalid && !seenItemCodes.add(code);

            if (codeInvalid || qtyInvalid || duplicateInBatch) {
                r.setStatus(STATUS_HANG);
                if (duplicateInBatch) {
                    r.setRemark("批内明细码重复：" + code);
                }
            } else {
                r.setStatus(STATUS_OK);
                ok++;
            }

            TPostReportRow old = existed.get(r.getRowNo());
            if (old != null) {
                r.setId(old.getId());
                this.postReportRowMapper.updateById(r);
            } else {
                this.postReportRowMapper.insert(r);
            }
        }
        return ok;
    }

    @Override
    public List<TPostReportRow> listErrors(String batchNo) {
        List<TPostReportRow> list = this.postReportRowMapper.selectList(new QueryWrapper<TPostReportRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_HANG)
                .eq("del_flag", DEL_NORMAL)
                .orderByAsc("row_no"));
        return list == null ? new ArrayList<TPostReportRow>() : list;
    }
}
