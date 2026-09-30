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
 * “at 时刻生效”只有一个口径 {@link #effective}：未删除、现行、启用之日 ≤ at ＜ 交棒之日
 * （eff_end 不含）；起止与状态为空的行按存量数据兼容（视为无约束/现行）。定位、列表、
 * 计数、单条可用性全部复用它，不再各查各的。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostQualityRuleServiceImpl implements ITPostQualityRuleService {

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

    /** 达标率百分比合法区间（含端点），越界一律判 0 */
    private static final BigDecimal RATE_MIN = BigDecimal.ZERO;
    private static final BigDecimal RATE_MAX = new BigDecimal("100");

    @javax.annotation.Resource
    private TPostQualityRuleMapper postQualityRuleMapper;

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）+ 字符串绑定：
     * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
     * 使边界判定在"切换当日"这类用例上随机错档（实测踩到）。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TPostQualityRule selectTPostQualityRuleById(Long id) {
        return this.postQualityRuleMapper.selectById(id);
    }

    @Override
    public List<TPostQualityRule> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TPostQualityRule>();
        }
        List<TPostQualityRule> all = this.postQualityRuleMapper
                .selectList(new QueryWrapper<TPostQualityRule>().eq("del_flag", 0));
        List<TPostQualityRule> avail = new ArrayList<TPostQualityRule>();
        for (TPostQualityRule r : all) {
            if (effective(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        TPostQualityRule rule = findRule(ruleCode, at);
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(RATE_MIN) < 0 || input.compareTo(RATE_MAX) > 0) {
            return 0;
        }
        List<TPostQualityRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TPostQualityRule r = this.postQualityRuleMapper.selectById(id);
        return r != null && effective(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        // 与列表同源：统计的就是 listAvailable 的条数，不另写一套查询
        return listAvailable(at).size();
    }

    /**
     * 生效口径（全服务唯一出处）：
     * del_flag 空或 0、status 空或 0现行、eff_start 空或 ≤ at、eff_end 空或 ＞ at（不含交棒日）。
     */
    private boolean effective(TPostQualityRule r, Date at) {
        if (r == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != 0) {
            return false;
        }
        if (r.getEffStart() != null && ts(at).compareTo(ts(r.getEffStart())) < 0) {
            return false;
        }
        if (r.getEffEnd() != null && ts(at).compareTo(ts(r.getEffEnd())) >= 0) {
            return false;
        }
        return true;
    }

    /** 按代号取 at 时刻生效版本；多个重叠时以启用之日最新者为准，并列取 id 大的（确定性） */
    private TPostQualityRule findRule(String ruleCode, Date at) {
        List<TPostQualityRule> hit = this.postQualityRuleMapper.selectList(new QueryWrapper<TPostQualityRule>()
                .eq("del_flag", 0).eq("rule_code", ruleCode));
        TPostQualityRule picked = null;
        for (TPostQualityRule r : hit) {
            if (!effective(r, at)) {
                continue;
            }
            if (picked == null) {
                picked = r;
                continue;
            }
            Date s1 = r.getEffStart();
            Date s0 = picked.getEffStart();
            boolean newer = s1 != null && (s0 == null || s1.after(s0));
            boolean sameStart = (s1 == null && s0 == null) || (s1 != null && s1.equals(s0));
            if (newer || (sameStart && r.getId() != null
                    && (picked.getId() == null || r.getId() > picked.getId()))) {
                picked = r;
            }
        }
        return picked;
    }

    /**
     * 分档：等于上限取高一档。阈值必须按 1→2→3 齐全，缺一或输入越界按“无法判档”返回 0。
     */
    private int levelOf(TPostQualityRule rule, BigDecimal input) {
        if (rule == null || input == null
                || input.compareTo(RATE_MIN) < 0 || input.compareTo(RATE_MAX) > 0) {
            return 0;
        }
        BigDecimal t1 = rule.getTh1Max();
        BigDecimal t2 = rule.getTh2Max();
        BigDecimal t3 = rule.getTh3Max();
        if (t1 == null || t2 == null || t3 == null) {
            return 0;
        }
        if (input.compareTo(t1) <= 0) {
            return 1;
        }
        if (input.compareTo(t2) <= 0) {
            return 2;
        }
        if (input.compareTo(t3) <= 0) {
            return 3;
        }
        return 4;
    }
}
