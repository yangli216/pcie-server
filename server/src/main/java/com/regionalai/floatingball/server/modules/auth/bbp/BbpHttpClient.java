package com.regionalai.floatingball.server.modules.auth.bbp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
public class BbpHttpClient {

    private final ObjectMapper objectMapper;
    private final AdminAuthMode authMode;
    private final OutboundSecurityService outboundSecurityService;
    private final RestTemplate restTemplate;

    public BbpHttpClient(ObjectMapper objectMapper,
                         AdminAuthMode authMode,
                         OutboundSecurityService outboundSecurityService) {
        this.objectMapper = objectMapper;
        this.authMode = authMode;
        this.outboundSecurityService = outboundSecurityService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1000, authMode.bbpProperties().getConnectTimeoutMs()));
        factory.setReadTimeout(Math.max(1000, authMode.bbpProperties().getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    public RoleResult findRoles(String username, String password, String tenantId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("loginName", username);
        body.put("pwd", BbpPasswordUtils.md5Once(password));
        body.put("tenantId", tenantId);
        body.put("forAccessToken", true);
        BbpCookieJar cookies = new BbpCookieJar();
        JsonNode payload = exchange(HttpMethod.POST, endpoint("/logon/myRoles"), body, cookies, "BBP账号认证");
        requireCode(payload, 200, "BBP账号认证失败");
        JsonNode items = listNode(payload.get("body"), "BBP登录成功但未返回角色信息");
        List<BbpRoleView> roles = new ArrayList<BbpRoleView>();
        for (JsonNode item : items) {
            BbpRoleView role = mapRole(item);
            if (Boolean.TRUE.equals(role.getActive()) && tenantId.equals(role.getTenantId())
                && (!StringUtils.hasText(role.getLoginName()) || username.equals(role.getLoginName()))
                && StringUtils.hasText(role.getAuthorizationId())) {
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            throw new BusinessException("当前账号在该租户下没有可用的 BBP 角色");
        }
        roles.sort(Comparator.comparing(item -> text(item.getRoleName(), item.getRoleCode(), item.getAuthorizationId())));
        return new RoleResult(cookies, roles);
    }

    public void establishSession(BbpCookieJar cookies, String authorizationId, String departmentAuthorizationId) {
        String url = UriComponentsBuilder.fromHttpUrl(endpoint("/logon/myApps"))
            .queryParam("urt", authorizationId)
            .queryParam("deep", "3")
            .queryParam("platform", "256")
            .queryParam("login", "0")
            .queryParam("urtDept", StringUtils.hasText(departmentAuthorizationId) ? departmentAuthorizationId : "")
            .build(true)
            .toUriString();
        JsonNode payload = exchange(HttpMethod.GET, url, null, cookies, "BBP角色登录");
        requireCode(payload, 200, "BBP角色登录失败");
        if (!cookies.hasNonEmpty("tk")) {
            throw new BusinessException("BBP角色登录成功，但未下发 tk 登录凭证");
        }
    }

    public List<BbpOrganizationView> findOrganizations(BbpCookieJar cookies, String tenantId) {
        ArrayNode request = objectMapper.createArrayNode().add(tenantId);
        JsonNode payload = exchange(HttpMethod.POST, endpoint("/api/bbp.organization/findByTenantId"), request, cookies, "BBP机构目录查询");
        JsonNode items = directoryItems(payload, "BBP机构服务未返回机构清单");
        List<BbpOrganizationView> result = new ArrayList<BbpOrganizationView>();
        for (JsonNode item : items) {
            BbpOrganizationView view = new BbpOrganizationView();
            view.setId(firstText(item, "id"));
            view.setCode(firstText(item, "cd", "code", "orgCd"));
            view.setName(firstText(item, "name", "na", "orgName"));
            view.setOrgId(firstText(item, "orgId"));
            view.setTenantId(firstText(item, "tenantId"));
            view.setOrgType(firstText(item, "orgType"));
            view.setOrgTypeText(firstText(item, "orgTypeText"));
            view.setFullName(firstText(item, "fullName"));
            view.setParentId(firstText(item, "parent", "parentId"));
            view.setParentName(firstText(item, "parentText", "parentName"));
            view.setActive(firstBoolean(item, "active", "fgActive"));
            if (StringUtils.hasText(view.getId()) && (!StringUtils.hasText(view.getTenantId()) || tenantId.equals(view.getTenantId()))) {
                result.add(view);
            }
        }
        result.sort(Comparator.comparing(item -> text(item.getCode(), item.getName(), item.getId())));
        return result;
    }

    public List<BbpPersonView> findPersons(BbpCookieJar cookies, String orgId) {
        return findPersons(cookies, "/api/bbp.person/findByOrgId", orgId, "BBP机构人员目录查询");
    }

    public List<BbpPersonView> findPersonsByDepartment(BbpCookieJar cookies, String deptId) {
        return findPersons(cookies, "/api/bbp.person/findByDeptId", deptId, "BBP部门人员目录查询");
    }

    private List<BbpPersonView> findPersons(BbpCookieJar cookies,
                                            String path,
                                            String scopeId,
                                            String operation) {
        ArrayNode request = objectMapper.createArrayNode().add(scopeId);
        JsonNode payload = exchange(HttpMethod.POST, endpoint(path), request, cookies, operation);
        JsonNode items = directoryItems(payload, "BBP人员服务未返回人员清单");
        List<BbpPersonView> result = new ArrayList<BbpPersonView>();
        for (JsonNode item : items) {
            BbpPersonView view = new BbpPersonView();
            view.setId(firstText(item, "id"));
            view.setUserId(firstText(item, "userId", "idUser"));
            view.setPersonId(firstText(item, "personId", "idPerson", "id"));
            view.setLoginName(firstText(item, "loginName", "userCd", "cdUser", "account"));
            view.setTenantId(firstText(item, "tenantId"));
            view.setPersonType(firstText(item, "personType"));
            view.setPersonTypeText(firstText(item, "personTypeText"));
            view.setMpiId(firstText(item, "mpiId"));
            view.setMpi(firstText(item, "mpi"));
            view.setCode(firstText(item, "cd", "code"));
            view.setName(firstText(item, "na", "name", "userName", "displayName"));
            view.setDescription(firstText(item, "des", "description"));
            view.setPy(firstText(item, "py"));
            view.setWb(firstText(item, "wb"));
            view.setZj(firstText(item, "zj"));
            view.setInstr(firstText(item, "instr"));
            view.setOrgId(firstText(item, "orgId"));
            view.setOrgName(firstText(item, "orgIdText", "orgName"));
            view.setDepartmentId(firstText(item, "deptId", "departmentId", "idDept"));
            view.setDepartmentName(firstText(item, "deptIdText", "deptName", "departmentName", "naDept"));
            view.setMobile(firstText(item, "mobile"));
            view.setEmail(firstText(item, "email"));
            view.setFeature(firstText(item, "feature"));
            view.setCardType(firstText(item, "cardType"));
            view.setCardTypeText(firstText(item, "cardTypeText"));
            view.setCardId(firstText(item, "cardId"));
            view.setGender(firstText(item, "gender"));
            view.setGenderText(firstText(item, "genderText"));
            view.setBirthday(firstText(item, "birthday"));
            view.setAvatar(firstText(item, "avatar"));
            view.setActive(firstBoolean(item, "active", "fgActive"));
            view.setCreateDate(firstText(item, "createDate"));
            view.setModifyDate(firstText(item, "modifyDate"));
            view.setTitleType(firstText(item, "titleType"));
            view.setTitleTypeText(firstText(item, "titleTypeText"));
            if (StringUtils.hasText(view.getUserId()) || StringUtils.hasText(view.getPersonId())) {
                result.add(view);
            }
        }
        result.sort(Comparator.comparing(item -> text(item.getName(), item.getLoginName(), item.getUserId())));
        return result;
    }

    private JsonNode exchange(HttpMethod method,
                              String url,
                              JsonNode body,
                              BbpCookieJar cookies,
                              String operation) {
        OutboundSecurityService.OutboundCall call = outboundSecurityService.acquireHttp(url, operation);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        String cookieHeader = cookies.headerValue();
        if (StringUtils.hasText(cookieHeader)) {
            headers.set(HttpHeaders.COOKIE, cookieHeader);
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(call.getUrl(), method, new HttpEntity<JsonNode>(body, headers), String.class);
            cookies.capture(response.getHeaders());
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new BusinessException(operation + "返回 HTTP " + response.getStatusCodeValue());
            }
            JsonNode payload = objectMapper.readTree(response.getBody());
            call.success();
            return payload;
        } catch (BusinessException ex) {
            call.failure(ex);
            throw ex;
        } catch (RestClientException ex) {
            call.failure(ex);
            throw new BusinessException(operation + "暂时不可用，请检查 BBP 服务地址和网络");
        } catch (Exception ex) {
            call.failure(ex);
            throw new BusinessException(operation + "返回内容不是有效 JSON");
        }
    }

    private JsonNode directoryItems(JsonNode payload, String missingMessage) {
        if (payload != null && payload.isArray()) {
            return payload;
        }
        requireCode(payload, 0, 200, "BBP目录查询失败");
        return listNode(payload == null ? null : payload.get("body"), missingMessage);
    }

    private JsonNode listNode(JsonNode node, String missingMessage) {
        if (node != null && node.isTextual()) {
            try {
                node = objectMapper.readTree(node.asText());
            } catch (Exception ex) {
                throw new BusinessException(missingMessage);
            }
        }
        if (node != null && node.isArray()) {
            return node;
        }
        if (node != null && node.isObject()) {
            JsonNode items = node.get("items");
            if (items == null) {
                items = node.get("tokens");
            }
            if (items != null && items.isArray()) {
                return items;
            }
        }
        throw new BusinessException(missingMessage);
    }

    private void requireCode(JsonNode payload, int expected, String prefix) {
        requireCode(payload, new int[]{expected}, prefix);
    }

    private void requireCode(JsonNode payload, int expectedOne, int expectedTwo, String prefix) {
        requireCode(payload, new int[]{expectedOne, expectedTwo}, prefix);
    }

    private void requireCode(JsonNode payload, int[] expected, String prefix) {
        int actual = payload == null ? Integer.MIN_VALUE : payload.path("code").asInt(Integer.MIN_VALUE);
        for (int value : expected) {
            if (actual == value) {
                return;
            }
        }
        String message = firstText(payload, "message", "msg");
        throw new BusinessException(prefix + (StringUtils.hasText(message) ? "：" + message : ""));
    }

    private BbpRoleView mapRole(JsonNode item) {
        BbpRoleView role = new BbpRoleView();
        role.setAuthorizationId(firstText(item, "id", "authorizationId", "udId", "urt"));
        role.setRoleId(firstText(item, "roleId"));
        role.setRoleCode(firstText(item, "roleCd", "roleCode"));
        role.setRoleName(firstText(item, "roleName", "naRole"));
        role.setUserId(firstText(item, "userId"));
        role.setUserName(firstText(item, "userName", "displayName"));
        role.setLoginName(firstText(item, "loginName"));
        role.setTenantId(firstText(item, "tenantId"));
        role.setTenantName(firstText(item, "tenantName"));
        role.setOrgId(firstContextText(item, "orgId", "idOrg"));
        role.setOrgCode(firstContextText(item, "orgCd", "cdOrg"));
        role.setOrgName(firstContextText(item, "orgName", "naOrg", "orgPureName"));
        role.setDepartmentId(firstContextText(item, "deptId", "idDept"));
        role.setDepartmentName(firstContextText(item, "deptName", "naDept"));
        role.setDepartmentAuthorizationId(firstText(item, "ud_id", "urtDept"));
        role.setActive(firstBoolean(item, "active"));
        return role;
    }

    private String firstContextText(JsonNode node, String... fields) {
        String direct = firstText(node, fields);
        if (StringUtils.hasText(direct) || node == null) {
            return direct;
        }
        JsonNode context = node.get("userRoleDepts");
        if (context != null && context.isArray() && context.size() > 0) {
            context = context.get(0);
        }
        return firstText(context, fields);
    }

    private Boolean firstBoolean(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            String text = value.asText();
            if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String endpoint(String path) {
        return authMode.normalizeBaseUrl() + path;
    }

    private String text(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    public static class RoleResult {
        private final BbpCookieJar cookies;
        private final List<BbpRoleView> roles;

        RoleResult(BbpCookieJar cookies, List<BbpRoleView> roles) {
            this.cookies = cookies;
            this.roles = roles;
        }

        public BbpCookieJar getCookies() {
            return cookies;
        }

        public List<BbpRoleView> getRoles() {
            return roles;
        }
    }
}
