package com.fc.v2.service.impl;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TPostDueTaskMapper;
import com.fc.v2.model.auto.TPostDueTask;
import com.fc.v2.service.ITPostDueTaskService;

/**
 * 经营许可效期与备案年度核验到期告知事项 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * @author fuce
 */
@Service
public class TPostDueTaskServiceImpl implements ITPostDueTaskService {

    /** 开口告知窗口（天）的全局边界：提前 2~5 日开口，行内 amount 给出本行提前天数并夹到该区间 */
    private static final int WIN_FROM = 2;
    private static final int WIN_TO = 5;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TPostDueTaskMapper postDueTaskMapper;

    @Override
    public TPostDueTask selectTPostDueTaskById(Long id) {
        return this.postDueTaskMapper.selectById(id);
    }

    /**
     * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：JDBC 的 serverTimezone 与本机
     * 时区不对称，直接把 java.util.Date 作参数会整体偏移，使"恰好到期"这类边界用例错判。
     */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /**
     * 该时刻可处理的条目，口径只有一处，{@link #runOnce} 直接复用，不做第二套筛选：
     * 正常数据（未删除 + 未开口；存量 status 为空按未开口兼容）+ 已进入告知窗口。
     * 窗口：自 at 起至截止时刻前不超过本行提前天数（夹在 2~5 日），
     * 即"提前开口"起到"恰好到期/逾期"都在窗口内，边界用 <= 不漏。
     */
    @Override
    public List<TPostDueTask> listDue(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TPostDueTask>();
        }
        List<TPostDueTask> all = this.postDueTaskMapper.selectList(new QueryWrapper<TPostDueTask>()
                .eq("del_flag", 0)
                .and(w -> w.eq("status", STATUS_WAIT).or().isNull("status")));
        List<TPostDueTask> due = new java.util.ArrayList<TPostDueTask>();
        for (TPostDueTask r : all) {
            if (r.getDueAt() != null && isInWindow(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    /**
     * 执行一次：可处理条目逐条送达并**落状态**（成功 1已送达，送不下去 2送不下去），
     * 已落状态的条目下一轮自然退出窗口队列；单条失败（含落库异常）跳过继续，
     * 返回成功送达条数。无可处理条目返回 0（不再 get(0) 越界）。
     */
    @Override
    public int runOnce(Date at) {
        List<TPostDueTask> due = listDue(at);
        if (due.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (TPostDueTask r : due) {
            int result = deliverable(r) ? STATUS_DONE : STATUS_FAIL;
            try {
                r.setStatus(result);
                this.postDueTaskMapper.updateById(r);
                if (result == STATUS_DONE) {
                    ok++;
                }
            } catch (RuntimeException e) {
                // 单条落库失败不影响同批其余条目
                continue;
            }
        }
        return ok;
    }

    /** 是否送得出去：事项号与提前天数齐备才视为可送达，否则记"送不下去" */
    private boolean deliverable(TPostDueTask r) {
        return r.getItemNo() != null && !r.getItemNo().trim().isEmpty()
                && r.getAmount() != null && r.getAmount().signum() >= 0;
    }

    /** 窗口判定（同源）：截止时刻 <= at + 本行有效提前天数 即已开口；逾期/恰好到期包含在内 */
    private boolean isInWindow(TPostDueTask r, Date at) {
        Calendar bound = Calendar.getInstance();
        bound.setTime(at);
        bound.add(Calendar.DAY_OF_MONTH, (int) effectiveLeadDays(r));
        return ts(r.getDueAt()).compareTo(ts(bound.getTime())) <= 0;
    }

    /** 本行提前天数：行内 amount 为准，夹到全局 [WIN_FROM, WIN_TO]；缺失取上界 */
    private long effectiveLeadDays(TPostDueTask r) {
        if (r.getAmount() == null) {
            return WIN_TO;
        }
        long days = r.getAmount().longValue();
        if (days < WIN_FROM) {
            return WIN_FROM;
        }
        if (days > WIN_TO) {
            return WIN_TO;
        }
        return days;
    }
}
