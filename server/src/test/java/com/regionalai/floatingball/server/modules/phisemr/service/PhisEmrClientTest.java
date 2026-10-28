package com.regionalai.floatingball.server.modules.phisemr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpCookieJar;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.config.AdminSecurityProperties;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PhisEmrClientTest {
    @Test
    void listUsesHospitalWideCompletedOutpatientQueryWithinSessionOrganization() {
        ObjectMapper mapper = new ObjectMapper();
        AdminSecurityProperties security = new AdminSecurityProperties();
        security.getAuth().getBbp().setBaseUrl("http://127.0.0.1:8080/phis");
        OutboundSecurityProperties outbound = new OutboundSecurityProperties();
        outbound.setAllowAllHosts(true);
        outbound.setAllowPrivateNetwork(true);
        PhisEmrClient client = new PhisEmrClient(mapper, new AdminAuthMode(security), new EmrQcProperties(),
            new OutboundSecurityService(outbound), new PhisContentNormalizer(mapper));
        MockRestServiceServer mock = MockRestServiceServer.createServer(
            (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate"));
        mock.expect(once(), requestTo("http://127.0.0.1:8080/phis/api/otms.emr.rpcHistoryRecordService/queryHistoryRecord"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("[{\"idApp\":\"42\",\"userType\":\"0\",\"fgCheck\":\"2\","
                + "\"insertDept\":\"\",\"insertUser\":\"\",\"orgIdList\":[\"4061\"],"
                + "\"deptIdList\":[],\"userIdList\":[],\"deptMapList\":[]}]"))
            .andRespond(withSuccess("{\"code\":200,\"body\":{\"code\":200,\"data\":{\"list\":[],\"total\":0}}}", MediaType.APPLICATION_JSON));

        PhisEmrClient.ListPage page = client.list(new BbpCookieJar(), LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 27), null, null, null, "4061", 1, 50);

        assertEquals(0, page.getTotal());
        mock.verify();
    }
}
