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
 * 底档取数只有一处：{@link #resolveUsableArch} 同时负责“在册未注销”校验、字号回填与件量
 * 分档换算，新增和修改两个入口都走它，不再一个入口带在册/删除条件、另一个入口裸取。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TPostOutletCardServiceImpl extends ServiceImpl<TPostOutletCardMapper, TPostOutletCard> implements ITPostOutletCardService {

    @Autowired
    private TPostCompanyArchMapper postCompanyArchMapper;

    @Override
    public TPostOutletCard selectTPostOutletCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TPostOutletCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TPostOutletCard> selectTPostOutletCardList(Wrapper<TPostOutletCard> queryWrapper) {
        // 分页由控制器 startPage() 决定，这里不另写死页码，也不吞掉调用方的查询条件
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public int insertTPostOutletCard(TPostOutletCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        TPostCompanyArch refArch = resolveUsableArch(record.getSiteId());
        if (refArch == null) {
            return 0;
        }
        if (isDuplicateBillNo(record.getBillNo(), null)) {
            return 0;
        }
        applyArch(record, refArch);
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTPostOutletCard(TPostOutletCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (isDuplicateBillNo(record.getBillNo(), record.getId())) {
            return 0;
        }
        // 与新增同一口径：底档在册才放行，件量/企业有变动时分档随之重算
        TPostCompanyArch refArch = resolveUsableArch(record.getSiteId());
        if (refArch == null) {
            return 0;
        }
        applyArch(record, refArch);

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TPostOutletCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
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

    /** 取在册（del_flag=0 且 status!=1 注销）底档；不存在或已注销返回 null。全服务唯一底档取数点 */
    private TPostCompanyArch resolveUsableArch(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        TPostCompanyArch arch = postCompanyArchMapper.selectOne(new QueryWrapper<TPostCompanyArch>()
                .eq("id", siteId).eq("del_flag", 0));
        if (arch == null) {
            return null;
        }
        if (arch.getStatus() != null && arch.getStatus() == 1) {
            return null;
        }
        return arch;
    }

    /** 字号回填 + 件量分档，新增/修改共用，换算口径只有这一处 */
    private void applyArch(TPostOutletCard record, TPostCompanyArch arch) {
        record.setSiteNo(arch.getSiteNo());
        record.setGradeLevel(gradeLevelOf(record.getQty(), arch));
    }

    /**
     * 日均件量按底档三线上限折序位，等于上限取高一序位（30/90/240 → 1..4）。
     * 件量为空或阈值未维护齐时无法换算，返回 null，不臆造档位。
     */
    private Integer gradeLevelOf(BigDecimal qty, TPostCompanyArch arch) {
        if (qty == null || arch.getTh1Max() == null
                || arch.getTh2Max() == null || arch.getTh3Max() == null) {
            return null;
        }
        if (qty.compareTo(arch.getTh1Max()) <= 0) {
            return 1;
        }
        if (qty.compareTo(arch.getTh2Max()) <= 0) {
            return 2;
        }
        if (qty.compareTo(arch.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }

    private boolean isDuplicateBillNo(String billNo, Long excludeId) {
        if (StringUtils.isEmpty(billNo)) {
            return false;
        }
        QueryWrapper<TPostOutletCard> wrapper = new QueryWrapper<TPostOutletCard>()
                .eq("bill_no", billNo).eq("del_flag", 0);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        Integer dupCnt = this.baseMapper.selectCount(wrapper);
        return dupCnt != null && dupCnt > 0;
    }
}
