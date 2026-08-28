package com.regionalai.floatingball.server.modules.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.PasswordUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginRequest;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginResponse;
import com.regionalai.floatingball.server.modules.auth.dto.AdminPasswordChangeRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAdminAccessService;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.role.entity.AiRole;
import com.regionalai.floatingball.server.modules.role.mapper.AiRoleMapper;
import com.regionalai.floatingball.server.modules.user.entity.AiUser;
import com.regionalai.floatingball.server.modules.user.entity.AiUserRole;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserMapper;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final AiUserMapper aiUserMapper;
    private final AiUserRoleMapper aiUserRoleMapper;
    private final AiRoleMapper aiRoleMapper;
    private final AdminTokenService adminTokenService;
    private final DatabaseDialect databaseDialect;
    private final AdminAuthMode adminAuthMode;
    private final BbpAdminAccessService bbpAdminAccessService;

    public AdminAuthService(AiUserMapper aiUserMapper,
                            AiUserRoleMapper aiUserRoleMapper,
                            AiRoleMapper aiRoleMapper,
                            AdminTokenService adminTokenService,
                            DatabaseDialect databaseDialect,
                            AdminAuthMode adminAuthMode,
                            BbpAdminAccessService bbpAdminAccessService) {
        this.aiUserMapper = aiUserMapper;
        this.aiUserRoleMapper = aiUserRoleMapper;
        this.aiRoleMapper = aiRoleMapper;
        this.adminTokenService = adminTokenService;
        this.databaseDialect = databaseDialect;
        this.adminAuthMode = adminAuthMode;
        this.bbpAdminAccessService = bbpAdminAccessService;
    }

    public AdminLoginResponse login(AdminLoginRequest request) {
        if (adminAuthMode.isBbp()) {
            throw new BusinessException("当前已启用 BBP 统一账号认证，请使用 BBP 登录入口");
        }
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException("账号或密码不能为空");
        }
        AiUser user = aiUserMapper.selectOne(new LambdaQueryWrapper<AiUser>()
            .eq(AiUser::getCdUser, request.getUsername().trim())
            .eq(AiUser::getFgActive, "1")
            .last(databaseDialect.firstRows(1)));
        if (user == null || !"1".equals(user.getSdStatus()) || !PasswordUtils.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("admin login failed: invalid credentials. username={}", request.getUsername().trim());
            throw new BusinessException("账号或密码错误");
        }

        log.info("admin login succeeded. username={}", user.getCdUser());
        AdminCurrentUser currentUser = toCurrentUser(user, findRoleCodesByUserId(user.getIdUser()));
        currentUser.setAuthProvider("LOCAL");
        return adminTokenService.issue(currentUser);
    }

    public AdminLoginResponse loginWithBbp(String username, BbpRoleView bbpRole, String sessionId) {
        if (!adminAuthMode.isBbp()) {
            throw new BusinessException("当前未启用 BBP 认证模式");
        }
        if (!StringUtils.hasText(username) || bbpRole == null) {
            throw new BusinessException("BBP登录身份不完整");
        }
        String localUsername = adminAuthMode.resolveLocalUsername(username, bbpRole.getRoleCode());
        AdminCurrentUser currentUser;
        if (!adminAuthMode.isSystemAdministratorRole(username, bbpRole.getRoleCode())) {
            List<String> grantRoles = bbpAdminAccessService.findActiveRoleCodes(bbpRole);
            if (grantRoles.isEmpty()) {
                throw new BusinessException("BBP账号认证成功，但尚未获得 PCIE 后台访问权限");
            }
            currentUser = toBbpGrantedCurrentUser(username, bbpRole, grantRoles);
        } else {
            AiUser user = requireActiveUserByUsername(localUsername, "BBP账号认证成功，但尚未获得 PCIE 后台访问权限");
            List<String> roleCodes = findRoleCodesByUserId(user.getIdUser());
            if (roleCodes.isEmpty()) {
                throw new BusinessException("BBP账号认证成功，但未分配 PCIE 后台角色");
            }
            currentUser = toCurrentUser(user, roleCodes);
        }
        currentUser.setAuthProvider("BBP");
        currentUser.setAuthSessionId(sessionId);
        currentUser.setBbpTenantId(bbpRole.getTenantId());
        currentUser.setBbpUserId(bbpRole.getUserId());
        currentUser.setBbpRoleId(bbpRole.getRoleId());
        currentUser.setBbpOrgId(bbpRole.getOrgId());
        currentUser.setBbpOrgCode(bbpRole.getOrgCode());
        currentUser.setBbpOrgName(bbpRole.getOrgName());
        log.info("admin BBP login succeeded. bbpUsername={}, localUsername={}, tenantId={}, bbpUserId={}",
            username.trim(), currentUser.getCdUser(), bbpRole.getTenantId(), bbpRole.getUserId());
        return adminTokenService.issue(currentUser);
    }

    public AdminCurrentUser currentUser(AdminCurrentUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException("未登录");
        }
        return currentUser;
    }

    public void changePassword(AdminCurrentUser currentUser, AdminPasswordChangeRequest request) {
        if (currentUser == null || !StringUtils.hasText(currentUser.getIdUser())) {
            throw new BusinessException("未登录");
        }
        if ("BBP".equals(currentUser.getAuthProvider())) {
            throw new BusinessException("BBP统一账号的密码请在 PHIS 门户中修改");
        }
        validatePasswordChangeRequest(request);

        AiUser user = requireActiveUserById(currentUser.getIdUser(), "当前管理员不存在或已停用");
        if (!PasswordUtils.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException("当前密码错误");
        }
        if (request.getNewPassword().equals(request.getOldPassword())) {
            throw new BusinessException("新密码不能与当前密码相同");
        }

        user.setPasswordHash(PasswordUtils.sha256(request.getNewPassword()));
        aiUserMapper.updateById(user);
        log.info("admin password changed. username={}", currentUser.getCdUser());
    }

    public void bootstrapResetPassword(String username, String newPassword) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException("目标管理员账号不能为空");
        }
        validateRawPassword(newPassword, "重置密码");

        AiUser user = requireActiveUserByUsername(username.trim(), "目标管理员不存在或已停用");
        user.setPasswordHash(PasswordUtils.sha256(newPassword));
        aiUserMapper.updateById(user);
        log.info("admin bootstrap password reset. username={}", username.trim());
    }

    private List<String> findRoleCodesByUserId(String idUser) {
        List<AiUserRole> mappings = aiUserRoleMapper.selectList(new LambdaQueryWrapper<AiUserRole>()
            .eq(AiUserRole::getIdUser, idUser)
            .eq(AiUserRole::getFgActive, "1"));
        if (mappings.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, AiRole> roleMap = aiRoleMapper.selectBatchIds(mappings.stream()
                .map(AiUserRole::getIdRole)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .filter(item -> "1".equals(item.getFgActive()))
            .filter(item -> "1".equals(item.getSdStatus()))
            .collect(Collectors.toMap(AiRole::getIdRole, item -> item, (left, right) -> left));
        return mappings.stream()
            .map(item -> roleMap.get(item.getIdRole()))
            .filter(item -> item != null)
            .map(AiRole::getCdRole)
            .collect(Collectors.toList());
    }

    private AiUser requireActiveUserById(String idUser, String errorMessage) {
        AiUser user = aiUserMapper.selectById(idUser);
        if (user == null || !"1".equals(user.getFgActive()) || !"1".equals(user.getSdStatus())) {
            throw new BusinessException(errorMessage);
        }
        return user;
    }

    private AiUser requireActiveUserByUsername(String username, String errorMessage) {
        AiUser user = aiUserMapper.selectOne(new LambdaQueryWrapper<AiUser>()
            .eq(AiUser::getCdUser, username)
            .eq(AiUser::getFgActive, "1")
            .eq(AiUser::getSdStatus, "1")
            .last(databaseDialect.firstRows(1)));
        if (user == null) {
            throw new BusinessException(errorMessage);
        }
        return user;
    }

    private void validatePasswordChangeRequest(AdminPasswordChangeRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getOldPassword())) {
            throw new BusinessException("当前密码不能为空");
        }
        validateNewPassword(request.getNewPassword(), request.getConfirmPassword());
    }

    private void validateNewPassword(String newPassword, String confirmPassword) {
        validateRawPassword(newPassword, "新密码");
        if (!StringUtils.hasText(confirmPassword)) {
            throw new BusinessException("确认密码不能为空");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("两次输入的新密码不一致");
        }
    }

    private void validateRawPassword(String password, String fieldName) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(fieldName + "不能为空");
        }
        if (password.length() < 6) {
            throw new BusinessException(fieldName + "至少 6 位");
        }
    }

    private AdminCurrentUser toCurrentUser(AiUser user, List<String> roles) {
        AdminCurrentUser currentUser = new AdminCurrentUser();
        currentUser.setIdUser(user.getIdUser());
        currentUser.setCdUser(user.getCdUser());
        currentUser.setNaUser(user.getNaUser());
        currentUser.setIdOrg(user.getIdOrg());
        currentUser.setRoles(roles);
        return currentUser;
    }

    private AdminCurrentUser toBbpGrantedCurrentUser(String username,
                                                     BbpRoleView bbpRole,
                                                     List<String> roleCodes) {
        AdminCurrentUser currentUser = new AdminCurrentUser();
        currentUser.setIdUser(bbpRole.getUserId());
        currentUser.setCdUser(username.trim());
        currentUser.setNaUser(StringUtils.hasText(bbpRole.getUserName())
            ? bbpRole.getUserName().trim() : username.trim());
        currentUser.setIdOrg(bbpRole.getOrgId());
        currentUser.setRoles(roleCodes);
        return currentUser;
    }
}
