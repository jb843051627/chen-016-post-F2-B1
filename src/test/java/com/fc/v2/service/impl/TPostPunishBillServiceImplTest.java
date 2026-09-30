package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TPostPunishBillMapper;
import com.fc.v2.model.auto.TPostPunishBill;

/**
 * 逐级核签单：开次/已签/情形同源推进、两名点齐、否决落终态、退回回环节清票数、终态拒收。
 */
@ExtendWith(MockitoExtension.class)
class TPostPunishBillServiceImplTest {

    @Mock
    private TPostPunishBillMapper mapper;
    @InjectMocks
    private TPostPunishBillServiceImpl service;

    private TPostPunishBill bill(Integer node, Integer mode, Integer need, Integer signed, Integer status) {
        TPostPunishBill b = new TPostPunishBill();
        b.setId(1L);
        b.setNodeNo(node);
        b.setSignMode(mode);
        b.setNeedCount(need);
        b.setSignCount(signed);
        b.setStatus(status);
        return b;
    }

    @Test
    void approve_orMode_advancesNodeOnlyAfterNodeSigned() {
        TPostPunishBill b = bill(0, 0, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        TPostPunishBill r = service.approve(1L, "甲", "ok");
        assertEquals(Integer.valueOf(1), r.getNodeNo());
        assertEquals(Integer.valueOf(0), r.getSignCount(), "进下一开时本开已签清零");
        assertEquals(Integer.valueOf(0), r.getStatus());
    }

    @Test
    void approve_fourOpens_lastOnePasses() {
        TPostPunishBill b = bill(0, 0, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        for (int i = 0; i < 3; i++) {
            service.approve(1L, "甲", "ok");
        }
        assertEquals(Integer.valueOf(3), b.getNodeNo());
        TPostPunishBill fin = service.approve(1L, "甲", "ok");
        assertEquals(Integer.valueOf(1), fin.getStatus(), "末开签满必须核讫，不能停在在核");
        assertEquals(Integer.valueOf(3), fin.getNodeNo());
    }

    @Test
    void approve_andMode_needsTwoSignaturesBeforeAdvancing() {
        TPostPunishBill b = bill(0, 1, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        TPostPunishBill first = service.approve(1L, "甲", "ok");
        assertEquals(Integer.valueOf(0), first.getNodeNo(), "两名点齐：一票不能进下一开");
        assertEquals(Integer.valueOf(1), first.getSignCount());
        TPostPunishBill second = service.approve(1L, "乙", "ok");
        assertEquals(Integer.valueOf(1), second.getNodeNo(), "第二票签齐才进开");
        assertEquals(Integer.valueOf(0), second.getSignCount());
    }

    @Test
    void approve_explicitNeedCountThree_requiresThreeVotes() {
        TPostPunishBill b = bill(0, 0, 3, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        service.approve(1L, "甲", "x");
        service.approve(1L, "乙", "x");
        assertEquals(Integer.valueOf(0), b.getNodeNo());
        assertEquals(Integer.valueOf(2), b.getSignCount());
        service.approve(1L, "丙", "x");
        assertEquals(Integer.valueOf(1), b.getNodeNo());
        assertEquals(Integer.valueOf(0), b.getSignCount());
    }

    @Test
    void reject_setsVetoStatus_persists() {
        TPostPunishBill b = bill(0, 0, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        TPostPunishBill r = service.reject(1L, "甲", "否");
        assertEquals(Integer.valueOf(2), r.getStatus(), "否决必须真正改写为已打回，不能空更新");
        verify(mapper).updateById(b);
    }

    @Test
    void rollback_returnsNodeAndClearsSignCount_andBackToRunning() {
        TPostPunishBill b = bill(2, 0, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        TPostPunishBill r = service.rollback(1L, "退回");
        assertEquals(Integer.valueOf(1), r.getNodeNo());
        assertEquals(Integer.valueOf(0), r.getSignCount());
        assertEquals(Integer.valueOf(0), r.getStatus(), "环节退回后情形须回到在核");
    }

    @Test
    void rollback_partialVotesAtFirstNode_clearsVotesOnly() {
        TPostPunishBill b = bill(0, 1, null, 1, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        TPostPunishBill r = service.rollback(1L, "撤票");
        assertEquals(Integer.valueOf(0), r.getNodeNo(), "首开无环节可退，只清本开投票");
        assertEquals(Integer.valueOf(0), r.getSignCount());
    }

    @Test
    void rollback_atStartWithNoVotes_rejected() {
        TPostPunishBill b = bill(0, 0, null, 0, 0);
        when(mapper.selectById(1L)).thenReturn(b);
        assertNull(service.rollback(1L, "x"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void terminalBill_rejectsApproveRejectRollback() {
        TPostPunishBill passed = bill(3, 0, null, 0, 1);
        when(mapper.selectById(1L)).thenReturn(passed);
        assertNull(service.approve(1L, "甲", "x"));
        assertNull(service.reject(1L, "甲", "x"));
        assertNull(service.rollback(1L, "x"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void blankApprover_rejected() {
        assertNull(service.approve(1L, "  ", "x"));
        verify(mapper, never()).selectById(any());
    }
}
