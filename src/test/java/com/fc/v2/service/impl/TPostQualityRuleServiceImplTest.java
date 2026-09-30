package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TPostQualityRuleMapper;
import com.fc.v2.model.auto.TPostQualityRule;

/**
 * 质量判据线：生效区间 [起,止) 同源过滤、优先级取首、边界等于取高档、
 * 各入口（evaluate/top/usable/count）口径一致。
 */
@ExtendWith(MockitoExtension.class)
class TPostQualityRuleServiceImplTest {

    @Mock
    private TPostQualityRuleMapper mapper;
    @InjectMocks
    private TPostQualityRuleServiceImpl service;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private Date d(String s) throws Exception {
        return f.parse(s);
    }

    private TPostQualityRule rule(Long id, String code, int prio, String start, String end,
                                  String t1, String t2, String t3, Integer delFlag) throws Exception {
        TPostQualityRule r = new TPostQualityRule();
        r.setId(id);
        r.setRuleCode(code);
        r.setPriority(prio);
        r.setEffStart(start == null ? null : d(start));
        r.setEffEnd(end == null ? null : d(end));
        r.setTh1Max(t1 == null ? null : new BigDecimal(t1));
        r.setTh2Max(t2 == null ? null : new BigDecimal(t2));
        r.setTh3Max(t3 == null ? null : new BigDecimal(t3));
        r.setDelFlag(delFlag);
        return r;
    }

    @Test
    void listAvailable_filtersByEffectiveWindow_halfOpen() throws Exception {
        TPostQualityRule active = rule(1L, "A", 1, "2026-01-01 00:00:00", "2026-12-31 00:00:00", "60", "80", "95", 0);
        TPostQualityRule retired = rule(2L, "B", 9, "2025-01-01 00:00:00", "2026-06-01 00:00:00", "60", "80", "95", 0);
        TPostQualityRule future = rule(3L, "C", 9, "2026-10-01 00:00:00", null, "60", "80", "95", 0);
        TPostQualityRule deleted = rule(4L, "D", 9, null, null, "60", "80", "95", 1);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(active, retired, future, deleted));

        Date now = d("2026-09-30 00:00:00");
        assertEquals(1, service.listAvailable(now).size(), "只应留下在 [起,止) 内、未删除的 A");
        assertEquals(1, service.countAvailable(now), "角标计数与列表同源");
    }

    @Test
    void effEndIsExclusive_effStartInclusive() throws Exception {
        // end 恰好等于判定时刻 → 已交棒，不可用
        TPostQualityRule endsAt = rule(1L, "A", 1, null, "2026-09-30 00:00:00", "60", "80", "95", 0);
        // start 恰好等于判定时刻 → 刚启用，可用
        TPostQualityRule startsAt = rule(2L, "B", 1, "2026-09-30 00:00:00", null, "60", "80", "95", 0);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(endsAt, startsAt));
        Date now = d("2026-09-30 00:00:00");
        assertEquals(1, service.listAvailable(now).size());
        assertEquals("B", service.listAvailable(now).get(0).getRuleCode());
    }

    @Test
    void evaluateTop_picksHighestPriority_notFirstRow() throws Exception {
        // LOW 在查询结果里排第一，但优先级低；给它压低的阈值，使同一输入在两条规则下档位不同
        TPostQualityRule low = rule(1L, "LOW", 1, null, null, "10", "40", "80", 0);
        TPostQualityRule high = rule(2L, "HIGH", 10, null, null, "60", "80", "95", 0);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(Arrays.asList(low, high));
        Date now = d("2026-09-30 00:00:00");
        // 输入 50：按 LOW 是第 3 档，按 HIGH 是第 1 档；取 HIGH 即证明按优先级而非首行
        assertEquals(1, service.evaluateTop(new BigDecimal("50"), now));
    }

    @Test
    void evaluate_byCode_usesEffectiveVersion_boundaryGoesHigher() throws Exception {
        TPostQualityRule v1 = rule(1L, "R", 1, "2025-01-01 00:00:00", "2026-01-01 00:00:00", "30", "60", "90", 0);
        TPostQualityRule v2 = rule(2L, "R", 1, "2026-01-01 00:00:00", null, "30", "60", "90", 0);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(Arrays.asList(v1, v2));
        Date now = d("2026-09-30 00:00:00");
        assertEquals(1, service.evaluate("R", new BigDecimal("29.99"), now));
        assertEquals(2, service.evaluate("R", new BigDecimal("30"), now), "等于低界取高一档");
        assertEquals(2, service.evaluate("R", new BigDecimal("59.99"), now));
        assertEquals(3, service.evaluate("R", new BigDecimal("60"), now), "等于中界取高一档");
        assertEquals(4, service.evaluate("R", new BigDecimal("90.01"), now));
        assertEquals(0, service.evaluate("R", new BigDecimal("120"), now), "越界输入返回 0");
        assertEquals(0, service.evaluate("NOPE", new BigDecimal("50"), now), "无生效版本返回 0");
    }

    @Test
    void usable_reflectsWindow_notMerelyExists() throws Exception {
        TPostQualityRule active = rule(1L, "A", 1, null, null, "60", "80", "95", 0);
        TPostQualityRule expired = rule(2L, "B", 1, null, "2026-01-01 00:00:00", "60", "80", "95", 0);
        when(mapper.selectById(1L)).thenReturn(active);
        when(mapper.selectById(2L)).thenReturn(expired);
        Date now = d("2026-09-30 00:00:00");
        assertTrue(service.usable(1L, now));
        assertFalse(service.usable(2L, now), "存在但已交棒不可用，不能只看记录在不在");
        assertFalse(service.usable(null, now));
    }

    @Test
    void legacyNullDelFlag_treatedAsNormal() throws Exception {
        TPostQualityRule legacy = rule(1L, "A", 1, null, null, "60", "80", "95", null);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(Arrays.asList(legacy));
        Date now = d("2026-09-30 00:00:00");
        assertEquals(1, service.countAvailable(now), "存量 del_flag 为空按正常兼容");
    }
}
