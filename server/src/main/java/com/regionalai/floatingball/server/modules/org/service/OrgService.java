package com.regionalai.floatingball.server.modules.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.org.entity.AiOrg;
import com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class OrgService {

    private final AiOrgMapper aiOrgMapper;
    private final DatabaseDialect databaseDialect;

    public OrgService(AiOrgMapper aiOrgMapper,
                      DatabaseDialect databaseDialect) {
        this.aiOrgMapper = aiOrgMapper;
        this.databaseDialect = databaseDialect;
    }

    public PageResponse<AiOrg> list(long current, long size, String keyword, String idRegion, String sdStatus) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiOrg> page = new Page<AiOrg>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiOrg> wrapper = new LambdaQueryWrapper<AiOrg>()
            .eq(AiOrg::getFgActive, "1")
            .orderByAsc(AiOrg::getSortOrder)
            .orderByDesc(AiOrg::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiOrg::getNaOrg, keyword).or().like(AiOrg::getCdOrg, keyword));
        }
        if (StringUtils.hasText(idRegion)) {
            wrapper.eq(AiOrg::getIdRegion, idRegion.trim());
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiOrg::getSdStatus, sdStatus.trim());
        }
        Page<AiOrg> result = aiOrgMapper.selectPage(page, wrapper);
        return new PageResponse<AiOrg>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Transactional
    public AiOrg save(AiOrg org) {
        validateOrg(org);
        String cdOrg = org.getCdOrg().trim();
        ensureUniqueCode(cdOrg, null);
        org.setCdOrg(cdOrg);
        if (!StringUtils.hasText(org.getFgActive())) {
            org.setFgActive("1");
        }
        if (!StringUtils.hasText(org.getSdStatus())) {
            org.setSdStatus("1");
        }
        try {
            aiOrgMapper.insert(org);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("机构编码已存在");
        }
        return org;
    }

    @Transactional
    public AiOrg update(String idOrg, AiOrg org) {
        if (org == null) {
            throw new BusinessException("请求体不能为空");
        }
        org.setIdOrg(idOrg);
        validateOrg(org);
        String cdOrg = org.getCdOrg().trim();
        ensureUniqueCode(cdOrg, idOrg);
        org.setCdOrg(cdOrg);
        try {
            aiOrgMapper.updateById(org);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("机构编码已存在");
        }
        return aiOrgMapper.selectById(idOrg);
    }

    @Transactional
    public void invalidate(String idOrg) {
        updateStatus(idOrg, "0");
    }

    @Transactional
    public void enable(String idOrg) {
        updateStatus(idOrg, "1");
    }

    private void updateStatus(String idOrg, String sdStatus) {
        AiOrg org = aiOrgMapper.selectById(idOrg);
        if (org == null) {
            throw new BusinessException("机构不存在");
        }
        org.setSdStatus(sdStatus);
        aiOrgMapper.updateById(org);
    }

    private void validateOrg(AiOrg org) {
        if (org == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(org.getCdOrg())) {
            throw new BusinessException("机构编码不能为空");
        }
        if (!StringUtils.hasText(org.getNaOrg())) {
            throw new BusinessException("机构名称不能为空");
        }
    }

    private void ensureUniqueCode(String cdOrg, String excludeIdOrg) {
        LambdaQueryWrapper<AiOrg> wrapper = new LambdaQueryWrapper<AiOrg>()
            .eq(AiOrg::getCdOrg, cdOrg)
            .eq(AiOrg::getFgActive, "1");
        if (StringUtils.hasText(excludeIdOrg)) {
            wrapper.ne(AiOrg::getIdOrg, excludeIdOrg);
        }
        AiOrg existing = aiOrgMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
        if (existing != null) {
            throw new BusinessException("机构编码已存在");
        }
    }
}
