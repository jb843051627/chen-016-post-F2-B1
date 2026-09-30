package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TPostPunishBillMapper;
import com.fc.v2.model.auto.TPostPunishBill;
import com.fc.v2.service.ITPostPunishBillService;

/**
 * 违法行为查处逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 */
@Service
public class TPostPunishBillServiceImpl implements ITPostPunishBillService {

    /** 开次范围 0..3（共四开），在最后一开签满即核讫 */
    private static final int FIRST_NODE = 0;
    private static final int LAST_NODE = 3;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TPostPunishBillMapper postPunishBillMapper;

    @Override
    public TPostPunishBill selectTPostPunishBillById(Long id) {
        return this.postPunishBillMapper.selectById(id);
    }

    /**
     * 签批一票。环节（node_no）、本开已签人数（sign_count）、情形（status）一处推导：
     * 每投一票 sign_count+1，签满本开应签人数才进入下一开；最后一开签满即核讫。
     * 应签人数口径：need_count 已维护以其为准，否则按 sign_mode（任一人=1，两名点齐=2）。
     * 已核讫/已打回的终态单拒收任何签批。
     */
    @Override
    public TPostPunishBill approve(Long id, String approver, String comment) {
        if (approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TPostPunishBill r = this.postPunishBillMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }

        int node = r.getNodeNo() == null ? FIRST_NODE : Math.max(FIRST_NODE, r.getNodeNo());
        int signed = r.getSignCount() == null ? 0 : Math.max(0, r.getSignCount());
        int need = resolveNeedCount(r);
        r.setNeedCount(Integer.valueOf(need));

        signed++;
        if (signed >= need) {
            // 本开签满：已签清零，进入下一开；末开签满则核讫
            r.setSignCount(0);
            if (node >= LAST_NODE) {
                r.setNodeNo(LAST_NODE);
                r.setStatus(STATUS_PASS);
            } else {
                r.setNodeNo(node + 1);
                r.setStatus(STATUS_RUNNING);
            }
        } else {
            // 本开尚未签齐（两名点齐场景）：留在原开继续等签
            r.setNodeNo(node);
            r.setSignCount(signed);
            r.setStatus(STATUS_RUNNING);
        }
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    /**
     * 否决：单据落"已打回"终态。空更新不再发生——状态必须真正改写；终态单重复否决拒收。
     */
    @Override
    public TPostPunishBill reject(Long id, String approver, String comment) {
        if (approver == null || approver.trim().isEmpty()) {
            return null;
        }
        TPostPunishBill r = this.postPunishBillMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    /**
     * 退回上一环节：开次 -1、本开已签清零，情形回到"在核"——环节退了就不能还挂着已核讫。
     * 已打回的单不走退回（须重新报批）；最初一开且无已签时无环节可退，拒收。
     */
    @Override
    public TPostPunishBill rollback(Long id, String comment) {
        TPostPunishBill r = this.postPunishBillMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int node = r.getNodeNo() == null ? FIRST_NODE : r.getNodeNo();
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        if (node <= FIRST_NODE && signed <= 0) {
            return null;
        }
        if (signed > 0 && node <= FIRST_NODE) {
            // 首开只投了部分票：先撤回本开投票，不开次回退
            r.setSignCount(0);
        } else {
            r.setNodeNo(Math.max(FIRST_NODE, node - 1));
            r.setSignCount(0);
        }
        r.setStatus(STATUS_RUNNING);
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    /** 本开应签人数唯一口径：need_count 优先，缺省按并签办法兜底，并回写持久化 */
    private int resolveNeedCount(TPostPunishBill r) {
        if (r.getNeedCount() != null && r.getNeedCount() > 0) {
            return r.getNeedCount();
        }
        return r.getSignMode() != null && r.getSignMode() == MODE_AND ? 2 : 1;
    }

    private boolean isTerminal(TPostPunishBill r) {
        return r.getStatus() != null
                && (r.getStatus() == STATUS_PASS || r.getStatus() == STATUS_VETO);
    }
}
