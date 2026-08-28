package com.regionalai.floatingball.server.modules.region.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.region.entity.AiRegion;
import com.regionalai.floatingball.server.modules.region.mapper.AiRegionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RegionService {

    private final AiRegionMapper aiRegionMapper;

    public RegionService(AiRegionMapper aiRegionMapper) {
        this.aiRegionMapper = aiRegionMapper;
    }

    public PageResponse<AiRegion> list(long current, long size, String keyword, String sdStatus) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiRegion> page = new Page<AiRegion>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiRegion> wrapper = new LambdaQueryWrapper<AiRegion>()
            .eq(AiRegion::getFgActive, "1")
            .orderByAsc(AiRegion::getSortOrder)
            .orderByDesc(AiRegion::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(AiRegion::getNaRegion, keyword);
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiRegion::getSdStatus, sdStatus.trim());
        }
        Page<AiRegion> result = aiRegionMapper.selectPage(page, wrapper);
        return new PageResponse<AiRegion>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    public AiRegion save(AiRegion region) {
        if (!StringUtils.hasText(region.getNaRegion())) {
            throw new BusinessException("区域名称不能为空");
        }
        if (!StringUtils.hasText(region.getFgActive())) {
            region.setFgActive("1");
        }
        if (!StringUtils.hasText(region.getSdStatus())) {
            region.setSdStatus("1");
        }
        aiRegionMapper.insert(region);
        return region;
    }

    public AiRegion update(String idRegion, AiRegion region) {
        region.setIdRegion(idRegion);
        aiRegionMapper.updateById(region);
        return aiRegionMapper.selectById(idRegion);
    }

    public void invalidate(String idRegion) {
        updateStatus(idRegion, "0");
    }

    public void enable(String idRegion) {
        updateStatus(idRegion, "1");
    }

    private void updateStatus(String idRegion, String sdStatus) {
        AiRegion region = aiRegionMapper.selectById(idRegion);
        if (region == null) {
            throw new BusinessException("区域不存在");
        }
        region.setSdStatus(sdStatus);
        aiRegionMapper.updateById(region);
    }
}
