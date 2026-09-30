package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TPostQualityRuleMapper;
import com.fc.v2.model.auto.TPostQualityRule;
import com.fc.v2.service.ITPostQualityRuleService;

/**
 * 服务质量达标率判据线 Service业务层处理（rule-eval 形状）
 *
 * @author fuce
 */
@Service
public class TPostQualityRuleServiceImpl implements ITPostQualityRuleService {

    private static final BigDecimal RATE_MIN = BigDecimal.ZERO;
    private static final BigDecimal RATE_MAX = new BigDecimal("100");

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TPostQualityRule> PRIORITY_THEN_CODE_DESC = new Comparator<TPostQualityRule>() {
        @Override
        public int compare(TPostQualityRule a, TPostQualityRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TPostQualityRuleMapper postQualityRuleMapper;

    @Override
    public TPostQualityRule selectTPostQualityRuleById(Long id) {
        return this.postQualityRuleMapper.selectById(id);
    }

    /**
     * at 时刻可用规则，是全类唯一口径：{@link #evaluate} / {@link #evaluateTop} /
     * {@link #usable} / {@link #countAvailable} 都从这里取，不再各写各的筛选。
     * 条件：未删除（存量 del_flag 为空按正常兼容）+ 已启用且未交棒（区间 [effStart, effEnd)，
     * 起止为空视为不限）。时间只在内存里对已查出的 Date 比较，不走 JDBC 日期参数，
     * 绕开 serverTimezone 错位；结果按优先级降序、ruleCode 降序排列。
     */
    @Override
    public List<TPostQualityRule> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TPostQualityRule>();
        }
        List<TPostQualityRule> all = this.postQualityRuleMapper.selectList(
                new QueryWrapper<TPostQualityRule>()
                        .eq("del_flag", 0).or().isNull("del_flag"));
        List<TPostQualityRule> active = new ArrayList<TPostQualityRule>();
        for (TPostQualityRule r : all) {
            if (effectiveAt(r, at)) {
                active.add(r);
            }
        }
        Collections.sort(active, PRIORITY_THEN_CODE_DESC);
        return active;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || !validInput(input) || at == null) {
            return 0;
        }
        for (TPostQualityRule r : listAvailable(at)) {
            if (ruleCode.equals(r.getRuleCode())) {
                return levelOf(r, input);
            }
        }
        return 0;
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (!validInput(input) || at == null) {
            return 0;
        }
        List<TPostQualityRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        // 列表已按优先级降序排好，取首条与"让线顺位"同源
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TPostQualityRule r = this.postQualityRuleMapper.selectById(id);
        return r != null && effectiveAt(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /** 生效区间唯一判定：[effStart, effEnd)，空端不限；del_flag 为空的存量数据按正常兼容 */
    private boolean effectiveAt(TPostQualityRule r, Date at) {
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getEffStart() != null && r.getEffStart().after(at)) {
            return false;
        }
        if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
            return false;
        }
        return true;
    }

    private boolean validInput(BigDecimal input) {
        return input != null
                && input.compareTo(RATE_MIN) >= 0
                && input.compareTo(RATE_MAX) <= 0;
    }

    /**
     * 分档：**等于上限取高一档**——与网点卡件量折算同一口径（30/90/240 等值取高档），
     * 不允许两处换算一边含等号、一边不含。三档阈值有缺失判不了档时返回 0（调用方按不可用处理）。
     */
    private int levelOf(TPostQualityRule rule, BigDecimal input) {
        BigDecimal th1 = rule.getTh1Max();
        BigDecimal th2 = rule.getTh2Max();
        BigDecimal th3 = rule.getTh3Max();
        if (th1 == null || th2 == null || th3 == null) {
            return 0;
        }
        if (input.compareTo(th1) < 0) {
            return 1;
        }
        if (input.compareTo(th2) < 0) {
            return 2;
        }
        if (input.compareTo(th3) < 0) {
            return 3;
        }
        return 4;
    }
}
