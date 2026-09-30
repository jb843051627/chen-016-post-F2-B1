package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TPostDueTaskMapper;
import com.fc.v2.model.auto.TPostDueTask;

/**
 * 到期告知作业：空集合不越界、窗口边界（恰好到期）、提前天数夹取、
 * 处理结果落状态（已送达/送不下去）、单条失败跳过继续。
 */
@ExtendWith(MockitoExtension.class)
class TPostDueTaskServiceImplTest {

    @Mock
    private TPostDueTaskMapper mapper;
    @InjectMocks
    private TPostDueTaskServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private Date at() throws Exception {
        return f.parse("2026-09-30 09:00:00");
    }

    private TPostDueTask task(String itemNo, String dueAt, String leadDays, Integer status) throws Exception {
        TPostDueTask t = new TPostDueTask();
        t.setId(System.nanoTime());
        t.setItemNo(itemNo);
        t.setDueAt(f.parse(dueAt));
        t.setAmount(leadDays == null ? null : new BigDecimal(leadDays));
        t.setStatus(status);
        t.setDelFlag(0);
        return t;
    }

    @Test
    void runOnce_empty_returns0_withoutIndexAccess() {
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(new ArrayList<>());
        assertEquals(0, service.runOnce(new Date()));
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listDue_windowBoundaries() throws Exception {
        Date now = at();
        TPostDueTask boundary = task("T1", "2026-10-03 09:00:00", "3", 0);  // 恰好在开口边界
        TPostDueTask notYet = task("T2", "2026-10-04 09:00:00", "2", 0);    // 还差一天
        TPostDueTask overdue = task("T3", "2026-09-29 09:00:00", "5", 0);   // 已逾期
        TPostDueTask clampedLead = task("T4", "2026-10-07 09:00:00", "9", 0); // amount=9 夹到上界 5：7 天后仍在窗外
        when(mapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(boundary, notYet, overdue, clampedLead));

        List<TPostDueTask> due = service.listDue(now);
        List<String> nos = new ArrayList<>();
        for (TPostDueTask t : due) {
            nos.add(t.getItemNo());
        }
        assertTrue(nos.contains("T1"), "恰好到期（=边界）必须包含，不能用严格小于漏掉");
        assertTrue(nos.contains("T3"), "逾期条目必须包含");
        assertEquals(2, due.size());
    }

    @Test
    void runOnce_persistsStatus_doneAndFail_singleFailureSkipped() throws Exception {
        Date now = at();
        TPostDueTask good = task("OK", "2026-10-01 09:00:00", "3", 0);
        TPostDueTask undeliverable = task("  ", "2026-10-01 09:00:00", "3", 0);
        TPostDueTask boom = task("BOOM", "2026-10-01 09:00:00", "3", 0);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(good, undeliverable, boom));
        doThrow(new RuntimeException("db down")).when(mapper).updateById(boom);
        doReturn(1).when(mapper).updateById(good);
        doReturn(1).when(mapper).updateById(undeliverable);

        int ok = service.runOnce(now);
        assertEquals(1, ok, "只统计成功送达条数，落库异常/送不下去都不计入");
        assertEquals(Integer.valueOf(1), good.getStatus(), "成功须落已送达");
        assertEquals(Integer.valueOf(2), undeliverable.getStatus(), "送不下去须落终态，不能还挂未开口");
        verify(mapper).updateById(good);
        verify(mapper).updateById(undeliverable);
        verify(mapper).updateById(boom);
    }
}
