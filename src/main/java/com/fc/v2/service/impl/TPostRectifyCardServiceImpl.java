package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TPostRectifyCardMapper;
import com.fc.v2.model.auto.TPostRectifyCard;
import com.fc.v2.service.ITPostRectifyCardService;

/**
 * 责令整改事务卡 Service业务层处理（state-machine 形状：单据流转）
 *
 * @author fuce
 */
@Service
public class TPostRectifyCardServiceImpl implements ITPostRectifyCardService {

    private static final int MIN_STAGE = 0;
    private static final int MAX_STAGE = 3;
    /** 0未起（仅建卡未流转） 1在办 2已销案（终态） */
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TPostRectifyCardMapper postRectifyCardMapper;

    @Override
    public TPostRectifyCard selectTPostRectifyCardById(Long id) {
        return this.postRectifyCardMapper.selectById(id);
    }

    @Override
    public List<TPostRectifyCard> selectTPostRectifyCardList(QueryWrapper<TPostRectifyCard> queryWrapper) {
        return this.postRectifyCardMapper.selectList(queryWrapper);
    }

    /**
     * 推进一档（段次 +1）。段次与卡的落点**同源**：status 永远由 stage 推导，
     * 不允许两处各写各的。到达最后一段（复查 3）即销案；已销案的终态卡拒收任何流转。
     */
    @Override
    public TPostRectifyCard advance(Long id, String remark) {
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? MIN_STAGE : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        r.setStage(st + 1);
        r.setStatus(deriveStatus(r.getStage()));
        r.setLastAction(remark);
        this.postRectifyCardMapper.updateById(r);
        return r;
    }

    /**
     * 回退一档（段次 -1）。已销案终态、以及本就在最初段次的卡拒收（返回 null）。
     * 回退后仍属"在办"，status 随 stage 同源回落，不允许回退了环节却仍挂着已销案。
     */
    @Override
    public TPostRectifyCard rollback(Long id, String remark) {
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? MIN_STAGE : r.getStage();
        if (st <= MIN_STAGE) {
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(deriveStatus(r.getStage()));
        r.setLastAction(remark);
        this.postRectifyCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        r.setContent(remark);
        return this.postRectifyCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        return this.postRectifyCardMapper.deleteById(id) > 0;
    }

    /** 终态只认段次：走到最后一段（销案）即为终态；存量数据即使 status 漏写也按段次兜底 */
    private boolean isTerminal(TPostRectifyCard r) {
        return r.getStage() != null && r.getStage() >= MAX_STAGE;
    }

    /** 段次 → 落点，唯一口径：末段销案，其余在办（建卡未流转由调用方保留 0未起） */
    private int deriveStatus(int stage) {
        return stage >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE;
    }
}
