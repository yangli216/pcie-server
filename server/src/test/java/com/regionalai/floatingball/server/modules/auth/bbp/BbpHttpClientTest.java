package com.regionalai.floatingball.server.modules.auth.bbp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.config.AdminSecurityProperties;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbpHttpClientTest {

    private HttpServer server;
    private BbpHttpClient client;
    private final AtomicReference<String> roleRequest = new AtomicReference<String>();
    private final AtomicReference<String> appCookie = new AtomicReference<String>();
    private final AtomicReference<String> directoryCookie = new AtomicReference<String>();
    private final AtomicReference<String> organizationPersonRequest = new AtomicReference<String>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/phis/logon/myRoles", exchange -> {
            roleRequest.set(readBody(exchange));
            exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=session-1; Path=/phis; HttpOnly");
            respond(exchange, 200, "{\"code\":200,\"body\":[{\"id\":\"AUTH-1\",\"ud_id\":\"DEPT-AUTH-1\",\"userId\":\"USER-1\",\"roleId\":\"ROLE-1\",\"tenantId\":\"TENANT-1\",\"tenantName\":\"测试租户\",\"loginName\":\"doctor001\",\"roleName\":\"机构管理员\",\"roleCd\":\"orgAdmin\",\"userName\":\"张三\",\"userRoleDepts\":{\"orgId\":\"ORG-1\",\"orgCd\":\"H001\",\"orgName\":\"第一医院\"},\"active\":true}]}" );
        });
        server.createContext("/phis/logon/myApps", exchange -> {
            appCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Set-Cookie", "tk=tk-value; Path=/phis; HttpOnly");
            respond(exchange, 200, "{\"code\":200,\"body\":{}}" );
        });
        server.createContext("/phis/api/bbp.organization/findByTenantId", exchange -> {
            directoryCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            respond(exchange, 200, "{\"code\":200,\"body\":{\"items\":[{\"id\":\"ORG-1\",\"cd\":\"H001\",\"name\":\"第一医院\",\"tenantId\":\"TENANT-1\",\"active\":true}]}}" );
        });
        server.createContext("/phis/api/bbp.person/findByOrgId", exchange -> {
            organizationPersonRequest.set(readBody(exchange));
            respond(exchange, 200, "{\"code\":200,\"body\":[{"
                + "\"id\":\"5d5a58b3d001613e941723b8\","
                + "\"personId\":\"5d5a58b3d001613e941723b8\","
                + "\"tenantId\":\"zhongshan\","
                + "\"personType\":\"2X\","
                + "\"personTypeText\":\"医技\","
                + "\"mpiId\":\"3333\","
                + "\"mpi\":\"4444\","
                + "\"cd\":\"asdasd\","
                + "\"na\":\"qqq\","
                + "\"des\":\"人员描述\","
                + "\"py\":\"qqq\","
                + "\"wb\":\"qqq\","
                + "\"zj\":\"zj001\","
                + "\"instr\":\"asdasd,qqq,qqq,qqq,\","
                + "\"orgId\":\"5bff7f8c6bf7d10e884122fd\","
                + "\"orgIdText\":\"市二医院\","
                + "\"deptId\":\"5d5a5668d001613e9417233e\","
                + "\"deptIdText\":\"检验科\","
                + "\"mobile\":\"13800000000\","
                + "\"email\":\"doctor@example.test\","
                + "\"feature\":\"特色人员\","
                + "\"cardType\":\"1\","
                + "\"cardTypeText\":\"身份证\","
                + "\"cardId\":\"330622199610011111\","
                + "\"gender\":\"1\","
                + "\"genderText\":\"男\","
                + "\"birthday\":\"1996-10-01 00:00:00\","
                + "\"avatar\":\"AVATAR-1\","
                + "\"userId\":\"5d5a58b4d001613e941723b9\","
                + "\"active\":true,"
                + "\"createDate\":\"2019-08-19 16:07:16\","
                + "\"modifyDate\":\"2019-08-19 16:08:25\","
                + "\"titleType\":\"1\","
                + "\"titleTypeText\":\"临床\"}]}" );
        });
        server.createContext("/phis/api/bbp.person/findByDeptId", exchange ->
            respond(exchange, 200, "{\"code\":200,\"body\":[{\"id\":\"PERSON-1\",\"personId\":\"PERSON-1\",\"tenantId\":\"TENANT-1\",\"personType\":\"2X\",\"personTypeText\":\"医技\",\"cd\":\"P001\",\"na\":\"张三\",\"orgId\":\"ORG-1\",\"orgIdText\":\"第一医院\",\"deptId\":\"DEPT-1\",\"deptIdText\":\"检验科\",\"userId\":\"USER-1\",\"genderText\":\"男\",\"titleTypeText\":\"临床\",\"active\":true}]}"));
        server.start();

        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.getAuth().setMode("bbp");
        properties.getAuth().getBbp().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/phis/");
        AdminAuthMode authMode = new AdminAuthMode(properties);
        authMode.validate();
        OutboundSecurityProperties outboundProperties = new OutboundSecurityProperties();
        outboundProperties.setAllowAllHosts(true);
        outboundProperties.setAllowPrivateNetwork(true);
        client = new BbpHttpClient(new ObjectMapper(), authMode, new OutboundSecurityService(outboundProperties));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldAuthenticateAndReuseCookiesForDirectoryCalls() {
        BbpHttpClient.RoleResult result = client.findRoles("doctor001", "password", "TENANT-1");
        List<BbpRoleView> roles = result.getRoles();

        assertEquals(1, roles.size());
        assertEquals("AUTH-1", roles.get(0).getAuthorizationId());
        assertEquals("ORG-1", roles.get(0).getOrgId());
        assertEquals("第一医院", roles.get(0).getOrgName());
        assertTrue(roleRequest.get().contains("\"pwd\":\"5f4dcc3b5aa765d61d8327deb882cf99\""));

        client.establishSession(result.getCookies(), "AUTH-1", "DEPT-AUTH-1");
        assertTrue(appCookie.get().contains("JSESSIONID=session-1"));

        List<BbpOrganizationView> organizations = client.findOrganizations(result.getCookies(), "TENANT-1");
        assertEquals("第一医院", organizations.get(0).getName());
        assertTrue(directoryCookie.get().contains("tk=tk-value"));

        List<BbpPersonView> persons = client.findPersons(result.getCookies(), "5bff7f8c6bf7d10e884122fd");
        assertEquals("[\"5bff7f8c6bf7d10e884122fd\"]", organizationPersonRequest.get());
        BbpPersonView person = persons.get(0);
        assertEquals("5d5a58b3d001613e941723b8", person.getId());
        assertEquals("5d5a58b3d001613e941723b8", person.getPersonId());
        assertEquals("5d5a58b4d001613e941723b9", person.getUserId());
        assertEquals("zhongshan", person.getTenantId());
        assertEquals("2X", person.getPersonType());
        assertEquals("医技", person.getPersonTypeText());
        assertEquals("3333", person.getMpiId());
        assertEquals("4444", person.getMpi());
        assertEquals("asdasd", person.getCode());
        assertEquals("qqq", person.getName());
        assertEquals("人员描述", person.getDescription());
        assertEquals("qqq", person.getPy());
        assertEquals("qqq", person.getWb());
        assertEquals("zj001", person.getZj());
        assertEquals("asdasd,qqq,qqq,qqq,", person.getInstr());
        assertEquals("5bff7f8c6bf7d10e884122fd", person.getOrgId());
        assertEquals("市二医院", person.getOrgName());
        assertEquals("5d5a5668d001613e9417233e", person.getDepartmentId());
        assertEquals("检验科", person.getDepartmentName());
        assertEquals("13800000000", person.getMobile());
        assertEquals("doctor@example.test", person.getEmail());
        assertEquals("特色人员", person.getFeature());
        assertEquals("1", person.getCardType());
        assertEquals("身份证", person.getCardTypeText());
        assertEquals("330622199610011111", person.getCardId());
        assertEquals("1", person.getGender());
        assertEquals("男", person.getGenderText());
        assertEquals("1996-10-01 00:00:00", person.getBirthday());
        assertEquals("AVATAR-1", person.getAvatar());
        assertTrue(person.getActive());
        assertEquals("2019-08-19 16:07:16", person.getCreateDate());
        assertEquals("2019-08-19 16:08:25", person.getModifyDate());
        assertEquals("1", person.getTitleType());
        assertEquals("临床", person.getTitleTypeText());

        List<BbpPersonView> departmentPersons = client.findPersonsByDepartment(result.getCookies(), "DEPT-1");
        assertEquals("检验科", departmentPersons.get(0).getDepartmentName());
        assertEquals("医技", departmentPersons.get(0).getPersonTypeText());
        assertEquals("临床", departmentPersons.get(0).getTitleTypeText());
    }

    @Test
    void shouldNotHashAnExistingMd5Again() {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99",
            BbpPasswordUtils.md5Once("5F4DCC3B5AA765D61D8327DEB882CF99"));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        InputStream input = exchange.getRequestBody();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
