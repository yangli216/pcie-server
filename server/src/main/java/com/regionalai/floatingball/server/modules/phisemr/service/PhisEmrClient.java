package com.regionalai.floatingball.server.modules.phisemr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpCookieJar;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class PhisEmrClient {

    private static final String HISTORY_SERVICE = "otms.emr.rpcHistoryRecordService";
    private static final String HISTORY_METHOD = "queryHistoryRecord";
    private static final String CONTENT_SERVICE = "otms.mb.rpcElectronicMedicalRecordWritingService";
    private static final String CONTENT_METHOD = "getMedContentPreview";
    private static final String ID_APP_OUTPATIENT = "42";
    private static final int UPSTREAM_PAGE_SIZE = 50;

    private final ObjectMapper objectMapper;
    private final AdminAuthMode authMode;
    private final OutboundSecurityService outboundSecurityService;
    private final PhisContentNormalizer normalizer;
    private final RestTemplate restTemplate;

    public PhisEmrClient(ObjectMapper objectMapper,
                         AdminAuthMode authMode,
                         EmrQcProperties properties,
                         OutboundSecurityService outboundSecurityService,
                         PhisContentNormalizer normalizer) {
        this.objectMapper = objectMapper;
        this.authMode = authMode;
        this.outboundSecurityService = outboundSecurityService;
        this.normalizer = normalizer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1000, properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Math.max(1000, properties.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
    }

    public ListPage list(BbpCookieJar cookies,
                         LocalDate dateFrom,
                         LocalDate dateTo,
                         String patientName,
                         String idCard,
                         String insertUser,
                         String hisOrgId,
                         int pageNum,
                         int pageSize) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("idApp", ID_APP_OUTPATIENT);
        param.put("idHospital", "");
        param.put("userType", "0");
        param.put("idArchives", "");
        param.put("insertDept", "");
        param.put("beginDate", dateFrom == null ? "" : dateFrom.toString());
        param.put("endDate", dateTo == null ? "" : dateTo.toString());
        param.put("fgCheck", "2");
        param.put("insertUser", text(insertUser));
        param.put("name", text(patientName));
        param.put("idCard", text(idCard));
        param.put("pageNum", pageNum);
        param.put("pageSize", pageSize > 0 ? pageSize : UPSTREAM_PAGE_SIZE);
        param.putArray("deptIdList");
        param.putArray("userIdList");
        param.putArray("deptMapList");
        ArrayNode orgIdList = param.putArray("orgIdList");
        if (StringUtils.hasText(hisOrgId)) {
            orgIdList.add(hisOrgId.trim());
        }

        JsonNode data = invoke(HISTORY_SERVICE, HISTORY_METHOD, param, cookies);
        if (data == null || !data.path("list").isArray()) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历列表返回结构异常");
        }
        List<PhisEmrRecord> records = new ArrayList<PhisEmrRecord>();
        for (JsonNode item : data.path("list")) {
            records.add(mapRecord(item));
        }
        ListPage page = new ListPage();
        page.setRecords(records);
        page.setTotal(data.path("total").asInt(records.size()));
        return page;
    }

    public JsonNode detail(BbpCookieJar cookies, String idMedrecdoc) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("idMedrecdoc", idMedrecdoc);
        param.put("idApp", ID_APP_OUTPATIENT);
        param.put("printType", "1");
        JsonNode data = invoke(CONTENT_SERVICE, CONTENT_METHOD, param, cookies);
        if (data == null || !data.hasNonNull("xmlContent")) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历详情返回结构异常");
        }
        return data;
    }

    public JsonNode contentPreview(BbpCookieJar cookies, String idMedrecdoc) {
        return detail(cookies, idMedrecdoc);
    }

    private JsonNode invoke(String serviceId, String method, ObjectNode param, BbpCookieJar cookies) {
        String url = authMode.normalizeBaseUrl() + "/api/" + serviceId + "/" + method;
        ArrayNode body = objectMapper.createArrayNode().add(param);
        OutboundSecurityService.OutboundCall call = outboundSecurityService.acquireHttp(url, "EMR病历只读查询");
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String cookieHeader = cookies == null ? null : cookies.headerValue();
        if (StringUtils.hasText(cookieHeader)) {
            headers.set(HttpHeaders.COOKIE, cookieHeader);
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                call.getUrl(), HttpMethod.POST, new HttpEntity<JsonNode>(body, headers), String.class);
            if (cookies != null) {
                cookies.capture(response.getHeaders());
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                call.failure(new IllegalStateException("http " + response.getStatusCodeValue()));
                throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务返回 HTTP " + response.getStatusCodeValue());
            }
            JsonNode payload = objectMapper.readTree(response.getBody());
            verifyPayload(payload);
            call.success();
            return payload.path("body").path("data");
        } catch (ServiceUnavailableException | ConflictException ex) {
            call.failure(ex);
            throw ex;
        } catch (HttpStatusCodeException ex) {
            call.failure(ex);
            int status = ex.getRawStatusCode();
            if (status == 401 || status == 403) {
                throw new ConflictException("EMR-PHIS-SESSION", "PHIS会话已失效，请重新登录后台");
            }
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务暂时不可用，请稍后重试");
        } catch (RestClientException ex) {
            call.failure(ex);
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务读取超时或网络异常，请稍后重试");
        } catch (Exception ex) {
            call.failure(ex);
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务返回内容不是有效 JSON");
        }
    }

    private void verifyPayload(JsonNode payload) {
        if (payload == null) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务返回空响应");
        }
        int topCode = payload.path("code").asInt(-1);
        int bodyCode = payload.path("body").path("code").asInt(200);
        if (topCode != 200 || bodyCode != 200) {
            if (topCode == 401 || topCode == 403 || bodyCode == 401 || bodyCode == 403) {
                throw new ConflictException("EMR-PHIS-SESSION", "PHIS会话已失效，请重新登录后台");
            }
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务返回业务错误");
        }
    }

    private PhisEmrRecord mapRecord(JsonNode item) {
        PhisEmrRecord record = new PhisEmrRecord();
        record.setIdMedrecdoc(value(item, "idMedrecdoc"));
        record.setIdTep(value(item, "idTep"));
        record.setTemplateName(value(item, "naMed"));
        record.setPatientName(value(item, "name"));
        record.setGenderCode(value(item, "sex"));
        record.setIdCard(value(item, "idCard"));
        record.setIdArchives(value(item, "idArchives"));
        record.setDoctorName(value(item, "insertUser"));
        record.setDeptName(value(item, "insertDept"));
        record.setHisOrgId(value(item, "idOrg"));
        record.setVisitTime(normalizer.parseDateTime(value(item, "titleTime")));
        record.setUpdateTime(normalizer.parseDateTime(value(item, "updateTime")));
        return record;
    }

    private String value(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    public static class ListPage {
        private List<PhisEmrRecord> records;
        private int total;

        public List<PhisEmrRecord> getRecords() {
            return records;
        }

        public void setRecords(List<PhisEmrRecord> records) {
            this.records = records;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }
}
