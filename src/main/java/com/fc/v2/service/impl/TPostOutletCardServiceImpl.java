package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TPostOutletCardMapper;
import com.fc.v2.mapper.auto.TPostCompanyArchMapper;
import com.fc.v2.model.auto.TPostOutletCard;
import com.fc.v2.model.auto.TPostCompanyArch;
import com.fc.v2.service.ITPostOutletCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 快递末端网点备案建档卡Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TPostOutletCardServiceImpl extends ServiceImpl<TPostOutletCardMapper, TPostOutletCard> implements ITPostOutletCardService {

    /** 底档情形：1 已注销，注销底档不得再建卡/改卡 */
    private static final int ARCH_CANCELLED = 1;

    @Autowired
    private TPostCompanyArchMapper postCompanyArchMapper;

    @Override
    public TPostOutletCard selectTPostOutletCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TPostOutletCard>()
                .eq("id", id)
                .and(activeRecord()));
    }

    /**
     * 列表查询：以调用方传入的条件为准（卡号/状态等筛选在 controller 一处拼装），
     * 这里只统一兜底"正常数据"口径（del_flag=0，存量 NULL 同正常），
     * 不再无视入参、不再强塞 status=0，也不在 service 里硬编码分页——分页由 PageHelper 在入口统一起。
     */
    @Override
    public List<TPostOutletCard> selectTPostOutletCardList(Wrapper<TPostOutletCard> queryWrapper) {
        // 以调用方传入的筛选条件为准，再统一补"正常数据"口径（del_flag=0，存量 NULL 同正常）；
        // 不再无视入参强塞 status=0，也不在 service 里硬编码分页——分页由 PageHelper 在入口统一起。
        QueryWrapper<TPostOutletCard> wrapper = queryWrapper == null
                ? new QueryWrapper<TPostOutletCard>()
                : (QueryWrapper<TPostOutletCard>) queryWrapper;
        wrapper.and(activeRecord());
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTPostOutletCard(TPostOutletCard record) {
        if (record == null) {
            return 0;
        }

        TPostCompanyArch refArch = loadActiveArch(record.getSiteId() == null ? null : record.getSiteId().longValue());
        if (refArch == null || isCancelled(refArch)) {
            return 0;
        }
        if (StringUtils.isNotEmpty(record.getBillNo()) && existsBillNo(record.getBillNo(), null)) {
            return 0;
        }

        record.setSiteNo(refArch.getSiteNo());
        record.setGradeLevel(resolveGrade(refArch, record.getQty()));
        record.setStatus(record.getStatus() == null ? 0 : record.getStatus());
        record.setDelFlag(0);
        record.setCreateBy(record.getBillNo());
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTPostOutletCard(TPostOutletCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        TPostOutletCard exists = this.baseMapper.selectById(record.getId());
        if (exists == null) {
            return 0;
        }
        if (StringUtils.isNotEmpty(record.getBillNo()) && existsBillNo(record.getBillNo(), record.getId())) {
            return 0;
        }

        // 改卡随版本走：底档字号与件量序位必须按当前底档重新折算，
        // 不能只更新页面字段、让 grade_level 停在旧版本口径上
        Integer siteId = record.getSiteId() == null ? exists.getSiteId() : record.getSiteId();
        TPostCompanyArch arch = loadActiveArch(siteId == null ? null : siteId.longValue());
        if (arch == null || isCancelled(arch)) {
            return 0;
        }
        record.setSiteId(siteId);
        record.setSiteNo(arch.getSiteNo());
        BigDecimal qty = record.getQty() == null ? exists.getQty() : record.getQty();
        record.setQty(qty);
        record.setGradeLevel(resolveGrade(arch, qty));

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TPostOutletCard>()
                .eq("id", record.getId())
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
    }

    @Override
    public int deleteTPostOutletCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTPostOutletCardById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    /** 底档查询唯一入口：正常数据口径（del_flag=0，存量 NULL 兼容），新增/改卡两处共用 */
    private TPostCompanyArch loadActiveArch(Long siteId) {
        if (siteId == null) {
            return null;
        }
        return this.postCompanyArchMapper.selectOne(new QueryWrapper<TPostCompanyArch>()
                .eq("id", siteId)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag")));
    }

    private boolean isCancelled(TPostCompanyArch arch) {
        return arch.getStatus() != null && arch.getStatus() == ARCH_CANCELLED;
    }

    private boolean existsBillNo(String billNo, Long excludeId) {
        QueryWrapper<TPostOutletCard> qw = new QueryWrapper<TPostOutletCard>()
                .eq("bill_no", billNo)
                .and(w -> w.eq("del_flag", 0).or().isNull("del_flag"));
        if (excludeId != null) {
            qw.ne("id", excludeId);
        }
        Integer cnt = this.baseMapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    /** 正常数据口径：del_flag=0 或存量 NULL */
    private java.util.function.Consumer<QueryWrapper<TPostOutletCard>> activeRecord() {
        return w -> w.eq("del_flag", 0).or().isNull("del_flag");
    }

    /**
     * 件量 → 服务序位的唯一折算口径，新增/改卡共用：
     * 按底档三线（30/90/240）分档，**等于上限算高一序位**（与底档种子注释一致）：
     * qty&lt;30→1，30&lt;=qty&lt;90→2，90&lt;=qty&lt;240→3，qty&gt;=240→4；件量缺失给 0。
     */
    private Integer resolveGrade(TPostCompanyArch arch, BigDecimal qty) {
        if (qty == null || arch.getTh1Max() == null
                || arch.getTh2Max() == null || arch.getTh3Max() == null) {
            return 0;
        }
        if (qty.compareTo(arch.getTh1Max()) < 0) {
            return 1;
        }
        if (qty.compareTo(arch.getTh2Max()) < 0) {
            return 2;
        }
        if (qty.compareTo(arch.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
