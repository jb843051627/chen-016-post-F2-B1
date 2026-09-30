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
 * 执行窗口只有一个口径 {@link #inWindow}：自“止点时刻提前 amount 天开口”起，到止点时刻
 * 止（两端都含）。列表 {@link #listDue} 与执行 {@link #runOnce} 同源于这套判定，
 * 不再一个看日期、一个拿 amount 正负凑数，避免看板条数和实际送达条数对不上。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TPostDueTaskServiceImpl implements ITPostDueTaskService {

    /** 条目情形 0未开口 1已送达 2送不下去（与表 status 注释同源） */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    private static final int DEL_NORMAL = 0;

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

    @Override
    public List<TPostDueTask> listDue(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TPostDueTask>();
        }
        List<TPostDueTask> all = this.postDueTaskMapper.selectList(new QueryWrapper<TPostDueTask>()
                .eq("del_flag", DEL_NORMAL)
                .eq("status", STATUS_WAIT));
        List<TPostDueTask> due = new java.util.ArrayList<TPostDueTask>();
        for (TPostDueTask r : all) {
            if (inWindow(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        // 处理对象与 listDue 完全同源；空窗直接返回 0，不再 get(0) 越界
        List<TPostDueTask> due = listDue(at);
        int ok = 0;
        for (TPostDueTask r : due) {
            try {
                r.setStatus(STATUS_DONE);
                this.postDueTaskMapper.updateById(r);
                ok++;
            } catch (RuntimeException e) {
                // 单条送达失败：记送不下去并跳过，继续处理其余条目，不拖垮整窗
                try {
                    r.setStatus(STATUS_FAIL);
                    this.postDueTaskMapper.updateById(r);
                } catch (RuntimeException ignored) {
                    // 落失败态也失败时保持未开口，下轮还能被 listDue 捞到
                }
            }
        }
        return ok;
    }

    /**
     * at 是否落在该条的告知窗口内：开口时刻 ≤ at ≤ 止点时刻（两端含）。
     * 开口时刻 = 止点时刻提前 amount 天；amount 为空按 0（止点当日才开口），负值按 0 兼容存量。
     */
    private boolean inWindow(TPostDueTask r, Date at) {
        Date deadline = r.getDueAt();
        if (deadline == null) {
            return false;
        }
        Date opened = deadline;
        if (r.getAmount() != null && r.getAmount().signum() > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(deadline);
            cal.add(Calendar.DAY_OF_MONTH, -r.getAmount().intValue());
            opened = cal.getTime();
        }
        String now = ts(at);
        return now.compareTo(ts(opened)) >= 0 && now.compareTo(ts(deadline)) <= 0;
    }
}
