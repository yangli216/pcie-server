package com.regionalai.floatingball.server.modules.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.PasswordUtils;
import com.regionalai.floatingball.server.modules.org.entity.AiOrg;
import com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper;
import com.regionalai.floatingball.server.modules.role.entity.AiRole;
import com.regionalai.floatingball.server.modules.role.mapper.AiRoleMapper;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserSaveRequest;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserView;
import com.regionalai.floatingball.server.modules.user.entity.AiUser;
import com.regionalai.floatingball.server.modules.user.entity.AiUserRole;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserMapper;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final AiUserMapper aiUserMapper;
    private final AiUserRoleMapper aiUserRoleMapper;
    private final AiRoleMapper aiRoleMapper;
    private final AiOrgMapper aiOrgMapper;
    private final DatabaseDialect databaseDialect;

    public UserService(AiUserMapper aiUserMapper,
                       AiUserRoleMapper aiUserRoleMapper,
                       AiRoleMapper aiRoleMapper,
                       AiOrgMapper aiOrgMapper,
                       DatabaseDialect databaseDialect) {
        this.aiUserMapper = aiUserMapper;
        this.aiUserRoleMapper = aiUserRoleMapper;
        this.aiRoleMapper = aiRoleMapper;
        this.aiOrgMapper = aiOrgMapper;
        this.databaseDialect = databaseDialect;
    }

    public PageResponse<AdminUserView> list(long current,
                                            long size,
                                            String keyword,
                                            String sdStatus,
                                            String idOrg,
                                            String idRole) {
        PageRequest pageRequest = PageRequest.of(current, size);
        List<String> roleFilteredUserIds = resolveRoleFilteredUserIds(idRole);
        if (StringUtils.hasText(idRole) && roleFilteredUserIds.isEmpty()) {
            return new PageResponse<AdminUserView>(
                pageRequest.getCurrent(),
                pageRequest.getSize(),
                0,
                Collections.<AdminUserView>emptyList()
            );
        }

        Page<AiUser> page = new Page<AiUser>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiUser> wrapper = new LambdaQueryWrapper<AiUser>()
            .eq(AiUser::getFgActive, "1")
            .orderByDesc(AiUser::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiUser::getCdUser, keyword).or().like(AiUser::getNaUser, keyword));
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiUser::getSdStatus, sdStatus);
        }
        if (StringUtils.hasText(idOrg)) {
            wrapper.eq(AiUser::getIdOrg, idOrg.trim());
        }
        if (!roleFilteredUserIds.isEmpty()) {
            wrapper.in(AiUser::getIdUser, roleFilteredUserIds);
        }

        Page<AiUser> result = aiUserMapper.selectPage(page, wrapper);
        return new PageResponse<AdminUserView>(result.getCurrent(), result.getSize(), result.getTotal(), toViews(result.getRecords()));
    }

    @Transactional
    public AdminUserView save(AdminUserSaveRequest request) {
        validateSaveRequest(request, true);
        ensureActiveOrg(request.getIdOrg());
        ensureUniqueUserCode(request.getCdUser(), null);
        List<AiRole> roles = requireActiveRoles(request.getRoleIds());

        AiUser user = new AiUser();
        user.setCdUser(request.getCdUser().trim());
        user.setNaUser(request.getNaUser().trim());
        user.setPasswordHash(PasswordUtils.sha256(request.getPassword()));
        user.setIdOrg(request.getIdOrg().trim());
        user.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : "1");
        user.setFgActive("1");
        try {
            aiUserMapper.insert(user);
            replaceRoles(user.getIdUser(), roles);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("登录账号已存在");
        }
        return toView(aiUserMapper.selectById(user.getIdUser()), roles, null);
    }

    @Transactional
    public AdminUserView update(String idUser, AdminUserSaveRequest request) {
        AiUser existing = requireActiveUser(idUser);
        validateSaveRequest(request, false);
        ensureActiveOrg(request.getIdOrg());
        ensureUniqueUserCode(request.getCdUser(), idUser);
        List<AiRole> roles = requireActiveRoles(request.getRoleIds());

        existing.setCdUser(request.getCdUser().trim());
        existing.setNaUser(request.getNaUser().trim());
        if (StringUtils.hasText(request.getPassword())) {
            existing.setPasswordHash(PasswordUtils.sha256(request.getPassword()));
        }
        existing.setIdOrg(request.getIdOrg().trim());
        existing.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : existing.getSdStatus());
        try {
            aiUserMapper.updateById(existing);
            replaceRoles(idUser, roles);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("登录账号已存在");
        }
        return toView(aiUserMapper.selectById(idUser), roles, null);
    }

    @Transactional
    public void invalidate(String idUser) {
        AiUser user = requireActiveUser(idUser);
        user.setFgActive("0");
        user.setSdStatus("0");
        aiUserMapper.updateById(user);

        List<AiUserRole> mappings = aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
            .eq(AiUserRole::getIdUser, idUser)
            .eq(AiUserRole::getFgActive, "1"));
        for (AiUserRole mapping : mappings) {
            mapping.setFgActive("0");
            aiUserRoleMapper.updateById(mapping);
        }
    }

    @Transactional
    public AdminUserView enable(String idUser) {
        return updateStatus(idUser, "1");
    }

    @Transactional
    public AdminUserView disable(String idUser) {
        return updateStatus(idUser, "0");
    }

    private List<String> resolveRoleFilteredUserIds(String idRole) {
        if (!StringUtils.hasText(idRole)) {
            return Collections.emptyList();
        }
        return aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
                .eq(AiUserRole::getIdRole, idRole)
                .eq(AiUserRole::getFgActive, "1"))
            .stream()
            .map(AiUserRole::getIdUser)
            .distinct()
            .collect(Collectors.toList());
    }

    private List<AdminUserView> toViews(List<AiUser> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, AiOrg> orgMap = aiOrgMapper.selectBatchIds(users.stream()
                .map(AiUser::getIdOrg)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .collect(Collectors.toMap(AiOrg::getIdOrg, item -> item, (left, right) -> left));
        Map<String, List<AiRole>> roleMapByUserId = buildRoleMapByUserId(users.stream()
            .map(AiUser::getIdUser)
            .collect(Collectors.toList()));
        return users.stream()
            .map(user -> toView(user, roleMapByUserId.get(user.getIdUser()), orgMap.get(user.getIdOrg())))
            .collect(Collectors.toList());
    }

    private AdminUserView toView(AiUser user, List<AiRole> roles, AiOrg org) {
        AdminUserView view = new AdminUserView();
        view.setIdUser(user.getIdUser());
        view.setCdUser(user.getCdUser());
        view.setNaUser(user.getNaUser());
        view.setIdOrg(user.getIdOrg());
        view.setNaOrg(org == null ? null : org.getNaOrg());
        view.setRoleIds((roles == null ? Collections.<AiRole>emptyList() : roles).stream()
            .map(AiRole::getIdRole)
            .collect(Collectors.toList()));
        view.setRoleNames((roles == null ? Collections.<AiRole>emptyList() : roles).stream()
            .map(AiRole::getNaRole)
            .collect(Collectors.toList()));
        view.setSdStatus(user.getSdStatus());
        view.setUpdateTime(user.getUpdateTime());
        return view;
    }

    private Map<String, List<AiRole>> buildRoleMapByUserId(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AiUserRole> mappings = aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
            .in(AiUserRole::getIdUser, userIds)
            .eq(AiUserRole::getFgActive, "1"));
        if (mappings.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, AiRole> roleMap = aiRoleMapper.selectBatchIds(mappings.stream()
                .map(AiUserRole::getIdRole)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .filter(item -> "1".equals(item.getFgActive()))
            .filter(item -> "1".equals(item.getSdStatus()))
            .collect(Collectors.toMap(AiRole::getIdRole, item -> item, (left, right) -> left));

        Map<String, List<AiRole>> result = new LinkedHashMap<String, List<AiRole>>();
        for (AiUserRole mapping : mappings) {
            AiRole role = roleMap.get(mapping.getIdRole());
            if (role == null) {
                continue;
            }
            List<AiRole> roles = result.get(mapping.getIdUser());
            if (roles == null) {
                roles = new ArrayList<AiRole>();
                result.put(mapping.getIdUser(), roles);
            }
            roles.add(role);
        }
        return result;
    }

    private void replaceRoles(String idUser, List<AiRole> roles) {
        List<AiUserRole> existing = aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
            .eq(AiUserRole::getIdUser, idUser)
            .eq(AiUserRole::getFgActive, "1"));
        for (AiUserRole item : existing) {
            item.setFgActive("0");
            aiUserRoleMapper.updateById(item);
        }

        for (AiRole role : roles) {
            AiUserRole mapping = new AiUserRole();
            mapping.setIdUser(idUser);
            mapping.setIdRole(role.getIdRole());
            mapping.setFgActive("1");
            aiUserRoleMapper.insert(mapping);
        }
    }

    private AiUser requireActiveUser(String idUser) {
        AiUser user = aiUserMapper.selectById(idUser);
        if (user == null || !"1".equals(user.getFgActive())) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    private AdminUserView updateStatus(String idUser, String sdStatus) {
        AiUser user = requireActiveUser(idUser);
        user.setSdStatus(sdStatus);
        aiUserMapper.updateById(user);
        return toView(aiUserMapper.selectById(idUser), buildRoleMapByUserId(Collections.singletonList(idUser)).get(idUser), null);
    }

    private void ensureActiveOrg(String idOrg) {
        AiOrg org = aiOrgMapper.selectOne(new LambdaQueryWrapper<AiOrg>()
            .eq(AiOrg::getIdOrg, idOrg)
            .eq(AiOrg::getFgActive, "1")
            .last(databaseDialect.firstRows(1)));
        if (org == null) {
            throw new BusinessException("所属机构不存在");
        }
    }

    private void ensureUniqueUserCode(String cdUser, String excludeIdUser) {
        LambdaQueryWrapper<AiUser> wrapper = new LambdaQueryWrapper<AiUser>()
            .eq(AiUser::getCdUser, cdUser.trim())
            .eq(AiUser::getFgActive, "1");
        if (StringUtils.hasText(excludeIdUser)) {
            wrapper.ne(AiUser::getIdUser, excludeIdUser);
        }
        AiUser existing = aiUserMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
        if (existing != null) {
            throw new BusinessException("登录账号已存在");
        }
    }

    private List<AiRole> requireActiveRoles(List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessException("至少选择一个角色");
        }
        List<AiRole> roles = aiRoleMapper.selectBatchIds(roleIds).stream()
            .filter(item -> item != null)
            .filter(item -> "1".equals(item.getFgActive()))
            .filter(item -> "1".equals(item.getSdStatus()))
            .collect(Collectors.toList());
        if (roles.size() != roleIds.size()) {
            throw new BusinessException("角色不存在或已停用");
        }
        return roles;
    }

    private void validateSaveRequest(AdminUserSaveRequest request, boolean creating) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getCdUser())) {
            throw new BusinessException("登录账号不能为空");
        }
        if (!StringUtils.hasText(request.getNaUser())) {
            throw new BusinessException("用户姓名不能为空");
        }
        if (creating && !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException("初始密码不能为空");
        }
        if (!StringUtils.hasText(request.getIdOrg())) {
            throw new BusinessException("所属机构不能为空");
        }
        if (request.getRoleIds() == null || request.getRoleIds().isEmpty()) {
            throw new BusinessException("至少选择一个角色");
        }
    }
}
