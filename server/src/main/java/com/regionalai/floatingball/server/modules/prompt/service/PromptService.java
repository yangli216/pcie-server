package com.regionalai.floatingball.server.modules.prompt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptDeltaVO;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptSaveRequest;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import com.regionalai.floatingball.server.modules.prompt.entity.AiPrompt;
import com.regionalai.floatingball.server.modules.prompt.mapper.AiPromptMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PromptService {

    private static final String STATUS_DRAFT = "0";
    private static final String STATUS_PUBLISHED = "1";
    private static final String STATUS_ARCHIVED = "2";

    private final AiPromptMapper aiPromptMapper;
    private final PromptDefaultCatalog defaultCatalog;

    public PromptService(AiPromptMapper aiPromptMapper, PromptDefaultCatalog defaultCatalog) {
        this.aiPromptMapper = aiPromptMapper;
        this.defaultCatalog = defaultCatalog;
    }

    public PromptDeltaVO getDelta(String orgId, String regionId, String version) {
        List<AiPrompt> visiblePrompts = findVisiblePrompts(orgId, regionId);
        String latestVersion = resolveLatestVersion(visiblePrompts);
        if (StringUtils.hasText(version) && version.equals(latestVersion)) {
            return new PromptDeltaVO(latestVersion, new ArrayList<PromptDeltaVO.RemotePrompt>());
        }

        Map<String, PromptDeltaVO.RemotePrompt> merged = new LinkedHashMap<String, PromptDeltaVO.RemotePrompt>();
        for (AiPrompt prompt : visiblePrompts) {
            if (!merged.containsKey(prompt.getCdPrompt())) {
                merged.put(prompt.getCdPrompt(), new PromptDeltaVO.RemotePrompt(
                    prompt.getCdPrompt(),
                    prompt.getSysPrompt(),
                    prompt.getUserTemplate(),
                    prompt.getVersionNum()
                ));
            }
        }
        return new PromptDeltaVO(latestVersion, new ArrayList<PromptDeltaVO.RemotePrompt>(merged.values()));
    }

    public String latestVisibleVersion(String orgId, String regionId) {
        return resolveLatestVersion(findVisiblePrompts(orgId, regionId));
    }

    public PageResponse<PromptView> list(long current,
                                         long size,
                                         String keyword,
                                         String sdStatus,
                                         String idOrg,
                                         String idRegion) {
        List<PromptView> all = new ArrayList<PromptView>();
        for (PromptView view : defaultCatalog.list()) {
            if (matches(view, keyword, sdStatus, idOrg, idRegion)) {
                all.add(view);
            }
        }

        LambdaQueryWrapper<AiPrompt> wrapper = new LambdaQueryWrapper<AiPrompt>()
            .eq(AiPrompt::getFgActive, "1")
            .orderByDesc(AiPrompt::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            final String text = keyword.trim();
            wrapper.and(q -> q.like(AiPrompt::getCdPrompt, text)
                .or()
                .like(AiPrompt::getNaPrompt, text));
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiPrompt::getSdStatus, sdStatus.trim());
        }
        if (StringUtils.hasText(idOrg)) {
            wrapper.eq(AiPrompt::getIdOrg, idOrg.trim());
        }
        if (StringUtils.hasText(idRegion)) {
            wrapper.eq(AiPrompt::getIdRegion, idRegion.trim());
        }

        List<AiPrompt> configured = aiPromptMapper.selectList(wrapper);
        for (AiPrompt prompt : configured) {
            all.add(toView(prompt, "configured", Boolean.FALSE));
        }

        PageRequest pageRequest = PageRequest.of(current, size);
        int fromIndex = pageRequest.fromIndex(all.size());
        int toIndex = pageRequest.toIndex(all.size());
        return new PageResponse<PromptView>(
            pageRequest.getCurrent(),
            pageRequest.getSize(),
            all.size(),
            new ArrayList<PromptView>(all.subList(fromIndex, toIndex))
        );
    }

    public PromptView resolveEffectivePrompt(String cdPrompt, String orgId, String regionId) {
        if (!StringUtils.hasText(cdPrompt)) {
            return null;
        }
        List<AiPrompt> prompts = findVisiblePrompts(orgId, regionId);
        for (AiPrompt prompt : prompts) {
            if (cdPrompt.trim().equals(prompt.getCdPrompt())) {
                return toView(prompt, "configured", Boolean.FALSE);
            }
        }
        return defaultCatalog.resolve(cdPrompt);
    }

    public PromptView save(PromptSaveRequest body) {
        AiPrompt entity = new AiPrompt();
        entity.setIdPrompt(UUID.randomUUID().toString().replace("-", ""));
        apply(entity, body, true);
        entity.setFgActive("1");
        entity.setInsertTime(LocalDateTime.now());
        entity.setUpdateTime(entity.getInsertTime());
        aiPromptMapper.insert(entity);
        return toView(entity, "configured", Boolean.FALSE);
    }

    public PromptView update(String idPrompt, PromptSaveRequest body) {
        if (!StringUtils.hasText(idPrompt) || idPrompt.startsWith("builtin:")) {
            throw new BusinessException("内置 Prompt 不能直接修改，请新建覆盖版本");
        }
        AiPrompt entity = loadPrompt(idPrompt);
        apply(entity, body, false);
        entity.setUpdateTime(LocalDateTime.now());
        aiPromptMapper.updateById(entity);
        return toView(entity, "configured", Boolean.FALSE);
    }

    public PromptView publish(String idPrompt) {
        AiPrompt entity = loadPrompt(idPrompt);
        entity.setSdStatus(STATUS_PUBLISHED);
        entity.setUpdateTime(LocalDateTime.now());
        archivePublishedSiblings(entity);
        aiPromptMapper.updateById(entity);
        return toView(entity, "configured", Boolean.FALSE);
    }

    public PromptView archive(String idPrompt) {
        AiPrompt entity = loadPrompt(idPrompt);
        entity.setSdStatus(STATUS_ARCHIVED);
        entity.setUpdateTime(LocalDateTime.now());
        aiPromptMapper.updateById(entity);
        return toView(entity, "configured", Boolean.FALSE);
    }

    public void invalidate(String idPrompt) {
        AiPrompt entity = loadPrompt(idPrompt);
        entity.setFgActive("0");
        entity.setUpdateTime(LocalDateTime.now());
        aiPromptMapper.updateById(entity);
    }

    private void archivePublishedSiblings(AiPrompt entity) {
        List<AiPrompt> siblings = aiPromptMapper.selectList(new LambdaQueryWrapper<AiPrompt>()
            .eq(AiPrompt::getFgActive, "1")
            .eq(AiPrompt::getSdStatus, STATUS_PUBLISHED)
            .eq(AiPrompt::getCdPrompt, entity.getCdPrompt())
            .eq(StringUtils.hasText(entity.getIdOrg()), AiPrompt::getIdOrg, entity.getIdOrg())
            .isNull(!StringUtils.hasText(entity.getIdOrg()), AiPrompt::getIdOrg)
            .eq(StringUtils.hasText(entity.getIdRegion()), AiPrompt::getIdRegion, entity.getIdRegion())
            .isNull(!StringUtils.hasText(entity.getIdRegion()), AiPrompt::getIdRegion));
        for (AiPrompt sibling : siblings) {
            if (!entity.getIdPrompt().equals(sibling.getIdPrompt())) {
                sibling.setSdStatus(STATUS_ARCHIVED);
                sibling.setUpdateTime(LocalDateTime.now());
                aiPromptMapper.updateById(sibling);
            }
        }
    }

    private AiPrompt loadPrompt(String idPrompt) {
        if (!StringUtils.hasText(idPrompt)) {
            throw new BusinessException("Prompt ID 不能为空");
        }
        AiPrompt entity = aiPromptMapper.selectById(idPrompt);
        if (entity == null || !"1".equals(entity.getFgActive())) {
            throw new BusinessException("Prompt 不存在或已删除");
        }
        return entity;
    }

    private void apply(AiPrompt entity, PromptSaveRequest body, boolean creating) {
        if (body == null) {
            throw new BusinessException("Prompt 请求不能为空");
        }
        String cdPrompt = trim(body.getCdPrompt());
        String naPrompt = trim(body.getNaPrompt());
        if (!StringUtils.hasText(cdPrompt)) {
            throw new BusinessException("Prompt 编码不能为空");
        }
        if (!StringUtils.hasText(naPrompt)) {
            throw new BusinessException("Prompt 名称不能为空");
        }
        entity.setCdPrompt(cdPrompt);
        entity.setNaPrompt(naPrompt);
        entity.setSysPrompt(trim(body.getSysPrompt()));
        entity.setUserTemplate(trim(body.getUserTemplate()));
        entity.setVersionNum(StringUtils.hasText(body.getVersionNum()) ? body.getVersionNum().trim() : (creating ? "v1.0" : entity.getVersionNum()));
        entity.setSdPromptType(StringUtils.hasText(body.getSdPromptType()) ? body.getSdPromptType().trim() : "consultation");
        entity.setSdStatus(StringUtils.hasText(body.getSdStatus()) ? body.getSdStatus().trim() : (creating ? STATUS_DRAFT : entity.getSdStatus()));
        entity.setIdOrg(trim(body.getIdOrg()));
        entity.setIdRegion(trim(body.getIdRegion()));
    }

    private boolean matches(PromptView view, String keyword, String sdStatus, String idOrg, String idRegion) {
        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim().toLowerCase();
            String combined = ((view.getCdPrompt() == null ? "" : view.getCdPrompt()) + " "
                + (view.getNaPrompt() == null ? "" : view.getNaPrompt())).toLowerCase();
            if (!combined.contains(text)) {
                return false;
            }
        }
        if (StringUtils.hasText(sdStatus) && !sdStatus.trim().equals(view.getSdStatus())) {
            return false;
        }
        if (StringUtils.hasText(idOrg) && !idOrg.trim().equals(view.getIdOrg())) {
            return false;
        }
        if (StringUtils.hasText(idRegion) && !idRegion.trim().equals(view.getIdRegion())) {
            return false;
        }
        return true;
    }

    private List<AiPrompt> findVisiblePrompts(String orgId, String regionId) {
        List<AiPrompt> prompts = aiPromptMapper.selectList(new LambdaQueryWrapper<AiPrompt>()
            .eq(AiPrompt::getFgActive, "1")
            .eq(AiPrompt::getSdStatus, STATUS_PUBLISHED)
            .and(q -> q.eq(StringUtils.hasText(orgId), AiPrompt::getIdOrg, orgId)
                .or()
                .isNull(AiPrompt::getIdOrg).eq(StringUtils.hasText(regionId), AiPrompt::getIdRegion, regionId)
                .or()
                .isNull(AiPrompt::getIdOrg).isNull(AiPrompt::getIdRegion))
            .orderByDesc(AiPrompt::getUpdateTime));
        prompts.sort((left, right) -> Integer.compare(score(right, orgId, regionId), score(left, orgId, regionId)));
        return prompts;
    }

    private String resolveLatestVersion(List<AiPrompt> prompts) {
        return prompts.isEmpty() ? "0" : prompts.get(0).getVersionNum();
    }

    private int score(AiPrompt prompt, String orgId, String regionId) {
        if (StringUtils.hasText(orgId) && orgId.equals(prompt.getIdOrg())) {
            return 3;
        }
        if (StringUtils.hasText(regionId) && regionId.equals(prompt.getIdRegion())) {
            return 2;
        }
        return 1;
    }

    private PromptView toView(AiPrompt prompt, String source, Boolean builtIn) {
        PromptView view = new PromptView();
        view.setIdPrompt(prompt.getIdPrompt());
        view.setCdPrompt(prompt.getCdPrompt());
        view.setNaPrompt(prompt.getNaPrompt());
        view.setSysPrompt(prompt.getSysPrompt());
        view.setUserTemplate(prompt.getUserTemplate());
        view.setVersionNum(prompt.getVersionNum());
        view.setSdPromptType(prompt.getSdPromptType());
        view.setSdStatus(prompt.getSdStatus());
        view.setIdOrg(prompt.getIdOrg());
        view.setIdRegion(prompt.getIdRegion());
        view.setSource(source);
        view.setBuiltIn(builtIn);
        view.setInsertTime(prompt.getInsertTime());
        view.setUpdateTime(prompt.getUpdateTime());
        return view;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
