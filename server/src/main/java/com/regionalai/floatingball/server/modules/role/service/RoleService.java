package com.regionalai.floatingball.server.modules.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.role.dto.AdminRoleSaveRequest;
import com.regionalai.floatingball.server.modules.role.entity.AiRole;
import com.regionalai.floatingball.server.modules.role.mapper.AiRoleMapper;
import com.regionalai.floatingball.server.modules.user.entity.AiUserRole;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class RoleService {

    private final AiRoleMapper aiRoleMapper;
    private final AiUserRoleMapper aiUserRoleMapper;
    private final DatabaseDialect databaseDialect;

    public RoleService(AiRoleMapper aiRoleMapper,
                       AiUserRoleMapper aiUserRoleMapper,
                       DatabaseDialect databaseDialect) {
        this.aiRoleMapper = aiRoleMapper;
        this.aiUserRoleMapper = aiUserRoleMapper;
        this.databaseDialect = databaseDialect;
    }

    public PageResponse<AiRole> list(long current, long size, String keyword) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiRole> page = new Page<AiRole>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiRole> wrapper = new LambdaQueryWrapper<AiRole>()
            .eq(AiRole::getFgActive, "1")
            .orderByDesc(AiRole::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiRole::getCdRole, keyword).or().like(AiRole::getNaRole, keyword));
        }
        Page<AiRole> result = aiRoleMapper.selectPage(page, wrapper);
        return new PageResponse<AiRole>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Transactional
    public AiRole save(AdminRoleSaveRequest request) {
        validateSaveRequest(request);
        ensureUniqueCode(request.getCdRole(), null);

        AiRole role = new AiRole();
        role.setCdRole(request.getCdRole().trim());
        role.setNaRole(request.getNaRole().trim());
        role.setDesRole(request.getDesRole());
        role.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : "1");
        role.setFgActive("1");
        try {
            aiRoleMapper.insert(role);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("角色编码已存在");
        }
        return role;
    }

    @Transactional
    public AiRole update(String idRole, AdminRoleSaveRequest request) {
        AiRole existing = requireActiveRole(idRole);
        validateSaveRequest(request);
        ensureUniqueCode(request.getCdRole(), idRole);

        existing.setCdRole(request.getCdRole().trim());
        existing.setNaRole(request.getNaRole().trim());
        existing.setDesRole(request.getDesRole());
        existing.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : existing.getSdStatus());
        try {
            aiRoleMapper.updateById(existing);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("角色编码已存在");
        }
        return aiRoleMapper.selectById(idRole);
    }

    @Transactional
    public void invalidate(String idRole) {
        AiRole role = requireActiveRole(idRole);
        role.setFgActive("0");
        role.setSdStatus("0");
        aiRoleMapper.updateById(role);

        List<AiUserRole> mappings = aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
            .eq(AiUserRole::getIdRole, idRole)
            .eq(AiUserRole::getFgActive, "1"));
        for (AiUserRole mapping : mappings) {
            mapping.setFgActive("0");
            aiUserRoleMapper.updateById(mapping);
        }
    }

    private AiRole requireActiveRole(String idRole) {
        AiRole role = aiRoleMapper.selectById(idRole);
        if (role == null || !"1".equals(role.getFgActive())) {
            throw new BusinessException("角色不存在");
        }
        return role;
    }

    private void ensureUniqueCode(String cdRole, String excludeIdRole) {
        LambdaQueryWrapper<AiRole> wrapper = new LambdaQueryWrapper<AiRole>()
            .eq(AiRole::getCdRole, cdRole.trim())
            .eq(AiRole::getFgActive, "1");
        if (StringUtils.hasText(excludeIdRole)) {
            wrapper.ne(AiRole::getIdRole, excludeIdRole);
        }
        AiRole existing = aiRoleMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
        if (existing != null) {
            throw new BusinessException("角色编码已存在");
        }
    }

    private void validateSaveRequest(AdminRoleSaveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getCdRole())) {
            throw new BusinessException("角色编码不能为空");
        }
        if (!StringUtils.hasText(request.getNaRole())) {
            throw new BusinessException("角色名称不能为空");
        }
    }
}
