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
 * 段次 stage：0下达 1整改中 2复查 3销案；落点 status 只由 {@link #statusOf} 从段次派生
 * （0未起 / 1在办 / 2已销案），推进、回退两个动作不允许各自手改状态，避免台账上
 * “环节走到复查、落点还停在未起”这类对不齐。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostRectifyCardServiceImpl implements ITPostRectifyCardService {

    private static final int STAGE_FIRST = 0;
    private static final int STAGE_CLOSED = 3;

    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_CLOSED = 2;

    private static final int DEL_NORMAL = 0;
    private static final int DEL_DELETED = 1;

    @javax.annotation.Resource
    private TPostRectifyCardMapper postRectifyCardMapper;

    @Override
    public TPostRectifyCard selectTPostRectifyCardById(Long id) {
        return this.postRectifyCardMapper.selectById(id);
    }

    @Override
    public List<TPostRectifyCard> selectTPostRectifyCardList(QueryWrapper<TPostRectifyCard> queryWrapper) {
        QueryWrapper<TPostRectifyCard> wrapper = queryWrapper == null
                ? new QueryWrapper<TPostRectifyCard>() : queryWrapper;
        // 台账只看正常单，叠加而不是替换调用方条件
        wrapper.eq("del_flag", DEL_NORMAL);
        return this.postRectifyCardMapper.selectList(wrapper);
    }

    @Override
    public TPostRectifyCard advance(Long id, String remark) {
        TPostRectifyCard r = loadActive(id);
        if (r == null) {
            return null;
        }
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        if (stage >= STAGE_CLOSED) {
            // 已销案不得再推进
            return null;
        }
        r.setStage(stage + 1);
        r.setStatus(statusOf(r.getStage()));
        r.setLastAction(remark);
        this.postRectifyCardMapper.updateById(r);
        return r;
    }

    @Override
    public TPostRectifyCard rollback(Long id, String remark) {
        TPostRectifyCard r = loadActive(id);
        if (r == null) {
            return null;
        }
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        if (stage <= STAGE_FIRST) {
            // 还在下达段，没有上一段可退
            return null;
        }
        r.setStage(stage - 1);
        r.setStatus(statusOf(r.getStage()));
        r.setLastAction(remark);
        this.postRectifyCardMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TPostRectifyCard r = loadActive(id);
        if (r == null || isClosed(r)) {
            // 已销案不得再改
            return false;
        }
        r.setContent(remark);
        return this.postRectifyCardMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || isClosed(r)) {
            // 已销案不得删除
            return false;
        }
        r.setDelFlag(DEL_DELETED);
        return this.postRectifyCardMapper.updateById(r) > 0;
    }

    /** 取未删除的在流单；不存在或已删返回 null */
    private TPostRectifyCard loadActive(Long id) {
        if (id == null) {
            return null;
        }
        TPostRectifyCard r = this.postRectifyCardMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() != DEL_NORMAL)) {
            return null;
        }
        return r;
    }

    private boolean isClosed(TPostRectifyCard r) {
        int stage = r.getStage() == null ? STAGE_FIRST : r.getStage();
        return stage >= STAGE_CLOSED;
    }

    /** 落点从段次单一推导：末段销案，中间各段在办，下达段未起 */
    private int statusOf(int stage) {
        if (stage >= STAGE_CLOSED) {
            return STATUS_CLOSED;
        }
        if (stage > STAGE_FIRST) {
            return STATUS_ACTIVE;
        }
        return STATUS_NOT_STARTED;
    }
}
