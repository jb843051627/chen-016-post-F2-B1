package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TPostPunishBillMapper;
import com.fc.v2.model.auto.TPostPunishBill;
import com.fc.v2.service.ITPostPunishBillService;

/**
 * 违法行为查处逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * 开次 node_no 取 0..3，报批情形只由 {@link #statusOf} 从开次派生：未满末开在核、
 * 到达末开核讫；否决另置打回态。签批、否决、退回三个动作不允许各自手改状态，
 * 否则会出现“开次已走完、报批情形还挂在核”这类两边对不上。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostPunishBillServiceImpl implements ITPostPunishBillService {

    /** 末开：开到第 3 开即核讫 */
    private static final int FINAL_NODE = 3;

    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TPostPunishBillMapper postPunishBillMapper;

    @Override
    public TPostPunishBill selectTPostPunishBillById(Long id) {
        return this.postPunishBillMapper.selectById(id);
    }

    @Override
    public TPostPunishBill approve(Long id, String approver, String comment) {
        TPostPunishBill r = loadRunning(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        r.setNodeNo(Math.min(node + 1, FINAL_NODE));
        r.setStatus(statusOf(r.getNodeNo()));
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    @Override
    public TPostPunishBill reject(Long id, String approver, String comment) {
        TPostPunishBill r = loadRunning(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    @Override
    public TPostPunishBill rollback(Long id, String comment) {
        TPostPunishBill r = loadRunning(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node <= 0) {
            // 第 0 开无上一开可退
            return null;
        }
        r.setNodeNo(node - 1);
        r.setStatus(statusOf(r.getNodeNo()));
        this.postPunishBillMapper.updateById(r);
        return r;
    }

    /** 取在核单；不存在、已删除或已核讫/已打回返回 null（终态不再受理动作） */
    private TPostPunishBill loadRunning(Long id) {
        if (id == null) {
            return null;
        }
        TPostPunishBill r = this.postPunishBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return null;
        }
        Integer status = r.getStatus();
        if (status != null && status != STATUS_RUNNING) {
            return null;
        }
        return r;
    }

    /** 报批情形从开次单一推导：开到末开核讫，否则在核 */
    private int statusOf(int node) {
        return node >= FINAL_NODE ? STATUS_PASS : STATUS_RUNNING;
    }
}
