package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TPostRectifyCardMapper;
import com.fc.v2.model.auto.TPostRectifyCard;

/**
 * 整改卡状态机：推进只能 +1、末段销案同源、终态拒收、回退 -1 且状态随段次回落。
 */
@ExtendWith(MockitoExtension.class)
class TPostRectifyCardServiceImplTest {

    @Mock
    private TPostRectifyCardMapper mapper;
    @InjectMocks
    private TPostRectifyCardServiceImpl service;

    private TPostRectifyCard card(Integer stage, Integer status) {
        TPostRectifyCard c = new TPostRectifyCard();
        c.setId(1L);
        c.setStage(stage);
        c.setStatus(status);
        return c;
    }

    @Test
    void advance_movesOneStageOnly_andStatusTracksStage() {
        TPostRectifyCard c = card(0, 0);
        when(mapper.selectById(1L)).thenReturn(c);
        TPostRectifyCard r = service.advance(1L, "下达");
        assertEquals(Integer.valueOf(1), r.getStage(), "一次只能推进一档，不得 +2");
        assertEquals(Integer.valueOf(1), r.getStatus(), "在办状态与段次同源");
        verify(mapper).updateById(c);
    }

    @Test
    void advance_toLastStage_marksTerminal() {
        TPostRectifyCard c = card(2, 1);
        when(mapper.selectById(1L)).thenReturn(c);
        TPostRectifyCard r = service.advance(1L, "复查销案");
        assertEquals(Integer.valueOf(3), r.getStage());
        assertEquals(Integer.valueOf(2), r.getStatus(), "到末段必须同源落已销案");
    }

    @Test
    void advance_beyondLast_isRejected() {
        TPostRectifyCard c = card(3, 2);
        when(mapper.selectById(1L)).thenReturn(c);
        assertNull(service.advance(1L, "再推"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void rollback_movesOneStage_andStatusDropsFromTerminalToActive() {
        // 段次 3 视为终态，按业务终态卡不可回退；这里验证段次 2 回退到 1 时状态同源回落
        TPostRectifyCard c = card(2, 1);
        when(mapper.selectById(1L)).thenReturn(c);
        TPostRectifyCard r = service.rollback(1L, "退回整改");
        assertEquals(Integer.valueOf(1), r.getStage(), "回退只退一档，不得直接归零");
        assertEquals(Integer.valueOf(1), r.getStatus());
    }

    @Test
    void rollback_atFirstStage_rejected() {
        TPostRectifyCard c = card(0, 1);
        when(mapper.selectById(1L)).thenReturn(c);
        assertNull(service.rollback(1L, "退无可退"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    void terminalCard_rejectsAdvanceRollbackEditRemove_evenIfStatusColumnStale() {
        // 存量数据：段次已到末段但 status 漏写，仍按段次兜底判终态
        TPostRectifyCard c = card(3, null);
        when(mapper.selectById(1L)).thenReturn(c);
        assertNull(service.advance(1L, "x"));
        assertNull(service.rollback(1L, "x"));
        assertFalse(service.updateContent(1L, "x"));
        assertFalse(service.remove(1L));
        verify(mapper, never()).updateById(any());
        verify(mapper, never()).deleteById(any());
    }

    @Test
    void updateContent_allowedWhenActive() {
        TPostRectifyCard c = card(1, 1);
        when(mapper.selectById(1L)).thenReturn(c);
        when(mapper.updateById(c)).thenReturn(1);
        assertTrue(service.updateContent(1L, "补记事"));
        assertEquals("补记事", c.getContent());
    }

    @Test
    void missingCard_returnsNull() {
        when(mapper.selectById(9L)).thenReturn(null);
        assertNull(service.advance(9L, "x"));
        assertNull(service.rollback(9L, "x"));
    }
}
