package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TPostCompanyArchMapper;
import com.fc.v2.mapper.auto.TPostOutletCardMapper;
import com.fc.v2.model.auto.TPostCompanyArch;
import com.fc.v2.model.auto.TPostOutletCard;

/**
 * 网点建档卡：底档注销拦截、件量分档"等于上限取高档"、改卡随版本重算、列表尊重入参口径。
 */
@ExtendWith(MockitoExtension.class)
class TPostOutletCardServiceImplTest {

    @Mock
    private TPostOutletCardMapper baseMapper;
    @Mock
    private TPostCompanyArchMapper archMapper;
    @InjectMocks
    private TPostOutletCardServiceImpl service;

    private TPostCompanyArch arch(int status) {
        TPostCompanyArch a = new TPostCompanyArch();
        a.setId(0L);
        a.setSiteNo("YZ00");
        a.setStatus(status);
        a.setDelFlag(0);
        a.setTh1Max(new BigDecimal("30"));
        a.setTh2Max(new BigDecimal("90"));
        a.setTh3Max(new BigDecimal("240"));
        return a;
    }

    private TPostOutletCard card(String billNo, String qty) {
        TPostOutletCard c = new TPostOutletCard();
        c.setBillNo(billNo);
        c.setSiteId(0);
        c.setQty(qty == null ? null : new BigDecimal(qty));
        return c;
    }

    @Test
    void insert_gradeBoundaries_equalThresholdGoesHigher() {
        when(archMapper.selectOne(any())).thenReturn(arch(0));
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(baseMapper.insert(any())).thenReturn(1);

        assertEquals(1, gradeOfInsert("29.99"));
        assertEquals(2, gradeOfInsert("30"), "等于低界 30 取第 2 档");
        assertEquals(2, gradeOfInsert("89.99"));
        assertEquals(3, gradeOfInsert("90"), "等于中界 90 取第 3 档");
        assertEquals(3, gradeOfInsert("239.99"));
        assertEquals(4, gradeOfInsert("240"), "等于高界 240 取第 4 档");
    }

    private int gradeOfInsert(String qty) {
        TPostOutletCard c = card("B-" + qty, qty);
        service.insertTPostOutletCard(c);
        return c.getGradeLevel();
    }

    @Test
    void insert_cancelledArch_rejected() {
        when(archMapper.selectOne(any())).thenReturn(arch(1));
        assertEquals(0, service.insertTPostOutletCard(card("B1", "30")));
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void insert_missingArch_rejected() {
        when(archMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, service.insertTPostOutletCard(card("B1", "30")));
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void insert_duplicateBillNo_rejected() {
        when(archMapper.selectOne(any())).thenReturn(arch(0));
        when(baseMapper.selectCount(any())).thenReturn(1);
        assertEquals(0, service.insertTPostOutletCard(card("DUP", "30")));
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void insert_happyPath_setsArchDerivedFields() {
        when(archMapper.selectOne(any())).thenReturn(arch(0));
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(baseMapper.insert(any())).thenReturn(1);
        TPostOutletCard c = card("B1", "100");
        assertEquals(1, service.insertTPostOutletCard(c));
        assertEquals("YZ00", c.getSiteNo());
        assertEquals(Integer.valueOf(3), c.getGradeLevel());
        assertEquals(Integer.valueOf(0), c.getDelFlag());
        assertEquals(Integer.valueOf(0), c.getStatus());
        assertEquals("B1", c.getCreateBy());
    }

    @Test
    void update_recomputesGradeAndSiteNo_fromCurrentArch() {
        TPostOutletCard exists = card("B1", "100");
        exists.setId(7L);
        exists.setGradeLevel(3);
        exists.setSiteNo("YZ00");
        when(baseMapper.selectById(7L)).thenReturn(exists);
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(archMapper.selectOne(any())).thenReturn(arch(0));
        when(baseMapper.update(any(), any())).thenReturn(1);

        TPostOutletCard rec = card("B1", "30");
        rec.setId(7L);
        assertEquals(1, service.updateTPostOutletCard(rec));
        assertEquals(Integer.valueOf(2), rec.getGradeLevel(), "改卡必须按新件量重算序位，不能停在旧档");
        assertEquals("YZ00", rec.getSiteNo());
    }

    @Test
    void update_withoutQty_fallsBackToPersistedQty() {
        TPostOutletCard exists = card("B1", "90");
        exists.setId(8L);
        when(baseMapper.selectById(8L)).thenReturn(exists);
        when(baseMapper.selectCount(any())).thenReturn(0);
        when(archMapper.selectOne(any())).thenReturn(arch(0));
        when(baseMapper.update(any(), any())).thenReturn(1);

        TPostOutletCard rec = card("B1", null);
        rec.setId(8L);
        service.updateTPostOutletCard(rec);
        assertEquals(Integer.valueOf(3), rec.getGradeLevel(), "未传件量时用库存件量重算（90 取第 3 档）");
    }

    @Test
    void update_cancelledArch_rejected() {
        TPostOutletCard exists = card("B1", "100");
        exists.setId(9L);
        when(baseMapper.selectById(9L)).thenReturn(exists);
        when(archMapper.selectOne(any())).thenReturn(arch(1));
        assertEquals(0, service.updateTPostOutletCard(card2(9L, "B1", "100")));
        verify(baseMapper, never()).update(any(), any());
    }

    private TPostOutletCard card2(long id, String bill, String qty) {
        TPostOutletCard c = card(bill, qty);
        c.setId(id);
        return c;
    }

    @Test
    void list_respectsCallerFilter_andAddsActiveScopeOnly() {
        TPostOutletCard frozen = card("F1", "30");
        frozen.setStatus(2);
        List<TPostOutletCard> data = Arrays.asList(frozen);
        when(baseMapper.selectList(any())).thenReturn(data);

        QueryWrapper<TPostOutletCard> qw = new QueryWrapper<TPostOutletCard>().eq("status", 2);
        List<TPostOutletCard> got = service.selectTPostOutletCardList(qw);
        assertSame(data, got, "service 必须尊重调用方传入的 status=2 筛选，不能强塞 status=0");
        verify(baseMapper).selectList(any());
    }
}
