package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TPostReportRowMapper;
import com.fc.v2.model.auto.TPostReportRow;

/**
 * 月度报表整批核收：覆盖空批越界、超限拒收、原行号、批内重复、整批幂等、
 * 合法行不被非法行连坐、成功/失败口径同源。
 */
@ExtendWith(MockitoExtension.class)
class TPostReportRowServiceImplTest {

    @Mock
    private TPostReportRowMapper mapper;
    @InjectMocks
    private TPostReportRowServiceImpl service;

    private TPostReportRow row(String code, String qty, Integer rowNo) {
        TPostReportRow r = new TPostReportRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setRowNo(rowNo);
        return r;
    }

    @Test
    void nullOrEmptyBatch_returns0_andNeverTouchesDb() {
        assertEquals(0, service.submitBatch("B1", null));
        assertEquals(0, service.submitBatch("B1", new ArrayList<TPostReportRow>()));
        verify(mapper, never()).insert(any());
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void overLimit_rejectedMinus1_nothingPersisted() {
        List<TPostReportRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("I" + i, "1", i + 1));
        }
        assertEquals(-1, service.submitBatch("BIG", rows));
        verify(mapper, never()).insert(any());
        verify(mapper, never()).selectCount(any());
    }

    @Test
    void validAndInvalidMixed_validRowsPersist_invalidKeepsOriginalRowNo() {
        when(mapper.selectCount(any())).thenReturn(0);
        List<TPostReportRow> rows = new ArrayList<>();
        rows.add(row("A", "10", 100));   // 合法，原行号 100
        rows.add(row("B", null, 200));   // 非法，原行号 200 必须保留
        rows.add(row("C", "0", 300));    // 非法（业务量须 > 0）
        rows.add(row("D", "5", null));   // 合法，行号缺省回退为提交序号 4

        int ok = service.submitBatch("B2", rows);
        assertEquals(2, ok, "合法行必须落库，非法行不得连坐");

        ArgumentCaptor<TPostReportRow> cap = ArgumentCaptor.forClass(TPostReportRow.class);
        verify(mapper, times(4)).insert(cap.capture());
        List<TPostReportRow> saved = cap.getAllValues();
        TPostReportRow bad = saved.get(1);
        assertEquals(Integer.valueOf(200), bad.getRowNo(), "失败明细行号须是原行号，不是序号");
        assertEquals(Integer.valueOf(2), bad.getStatus());
        TPostReportRow first = saved.get(0);
        assertEquals(Integer.valueOf(100), first.getRowNo());
        assertEquals(Integer.valueOf(1), first.getStatus());
        TPostReportRow last = saved.get(3);
        assertEquals(Integer.valueOf(4), last.getRowNo(), "缺省行号回退提交序号（从 1 起）");
        assertEquals(Integer.valueOf(1), last.getStatus());
        for (TPostReportRow r : saved) {
            assertEquals("B2", r.getBatchNo());
            assertEquals(Integer.valueOf(0), r.getDelFlag());
        }
    }

    @Test
    void duplicateItemCodeWithinBatch_secondRowMarkedFail() {
        when(mapper.selectCount(any())).thenReturn(0);
        List<TPostReportRow> rows = new ArrayList<>();
        rows.add(row("DUP", "1", 1));
        rows.add(row("DUP", "1", 2));
        assertEquals(1, service.submitBatch("B3", rows));
        ArgumentCaptor<TPostReportRow> cap = ArgumentCaptor.forClass(TPostReportRow.class);
        verify(mapper, times(2)).insert(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(0).getStatus());
        assertEquals(Integer.valueOf(2), cap.getAllValues().get(1).getStatus(), "同批同明细码第二行须挂起");
    }

    @Test
    void resubmitSameBatch_isIdempotent_noDoubledQuantity() {
        // 第一次提交：2 行合法
        when(mapper.selectCount(any())).thenReturn(0);
        List<TPostReportRow> rows = new ArrayList<>();
        rows.add(row("A", "1", 1));
        rows.add(row("B", "2", 2));
        assertEquals(2, service.submitBatch("B4", rows));

        // 第二次提交：批次已有 2 行（成功 2），直接返回上次口径，不再写任何行
        when(mapper.selectCount(any())).thenReturn(2);
        int again = service.submitBatch("B4", rows);
        assertEquals(2, again, "重复提交返回上次成功行数，不翻倍");
        verify(mapper, times(2)).insert(any());
    }

    @Test
    void listErrors_returnsRowsOrderedByRowNo() {
        List<TPostReportRow> errs = new ArrayList<>();
        errs.add(row("X", null, 5));
        when(mapper.selectList(any())).thenReturn(errs);
        List<TPostReportRow> got = service.listErrors("B5");
        assertEquals(1, got.size());
        assertNotNull(got.get(0));
        assertTrue(got.get(0).getRowNo() == 5);
    }
}
