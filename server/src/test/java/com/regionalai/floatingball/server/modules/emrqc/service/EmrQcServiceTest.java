package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.NotFoundException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpCookieJar;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpSessionRegistry;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDetailVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDoctorVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcRecordVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcReviewRequest;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcFinding;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcRecord;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisContentNormalizer;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisEmrClient;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisRecordCache;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisSessionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmrQcServiceTest {

    @Mock
    private EmrQcSchemaService schemaService;
    @Mock
    private PhisSessionScope sessionScope;
    @Mock
    private PhisEmrClient phisClient;
    @Mock
    private EmrQcAiClient aiClient;
    @Mock
    private EmrQcWriter writer;
    @Mock
    private EmrQcAuditService auditService;
    @Mock
    private ConfigService configService;
    @Mock
    private AiEmrQcRecordMapper recordMapper;
    @Mock
    private AiOrgMapper orgMapper;
    @Mock
    private BbpDirectoryService directoryService;

    private EmrQcProperties properties;
    private PhisRecordCache recordCache;
    private EmrQcService service;
    private BbpSessionRegistry registry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new EmrQcProperties();
        properties.setEnabled(true);
        registry = new BbpSessionRegistry();
        registry.register("s1", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000, new BbpRoleView());
        objectMapper = new ObjectMapper();
        recordCache = new PhisRecordCache();
        service = new EmrQcService(
            properties,
            schemaService,
            sessionScope,
            recordCache,
            phisClient,
            new PhisContentNormalizer(objectMapper),
            new SensitiveDataMasker(),
            aiClient,
            writer,
            auditService,
            configService,
            recordMapper,
            orgMapper,
            objectMapper,
            directoryService,
            new com.regionalai.floatingball.server.common.util.AesUtils("test-secret-key")
        );
    }

    @Test
    void listBuildsSessionAllowListAndDetailRejectsUnknownRecord() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record("A")));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), any(), any(), eq(1), eq(10)))
            .thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResponse<EmrQcRecordVO> result = service.list(user, null, null, null, null, null, "all", 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals("pending", result.getRecords().get(0).getQcStatus());
        assertEquals("A", result.getRecords().get(0).getIdMedrecdoc());

        NotFoundException exception = assertThrows(NotFoundException.class,
            () -> service.detail(user, "B", "req"));

        assertEquals("EMR-RECORD-NOT-FOUND", exception.getCode());
        verify(phisClient, never()).detail(any(), eq("B"));
    }

    @Test
    void detailAndAnalyzeRejectRecordsNeverReturnedByList() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());

        NotFoundException detailException = assertThrows(NotFoundException.class,
            () -> service.detail(user, "not-listed", "req"));
        NotFoundException analyzeException = assertThrows(NotFoundException.class,
            () -> service.analyze(user, "not-listed", true, "req"));

        assertEquals("EMR-RECORD-NOT-FOUND", detailException.getCode());
        assertEquals("EMR-RECORD-NOT-FOUND", analyzeException.getCode());
        verify(phisClient, never()).detail(any(), any());
        verify(aiClient, never()).analyze(any(), any());
    }

    @Test
    void detailAddsCleanHtmlPreviewAndKeepsCanonicalTextAndHashIndependent() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("xmlContent", "[{\"ID\":\"p1\",\"ARTICLE\":\"主诉\",\"eles\":["
            + "{\"NAME\":\"chief\",\"DEFAULTVALUE\":\"主诉\",\"TEXT\":\"<b>咳嗽</b>3天\",\"HIDDEN\":\"0\"},"
            + "{\"NAME\":\"hidden\",\"DEFAULTVALUE\":\"隐藏\",\"TEXT\":\"不应出现\",\"HIDDEN\":\"true\"}]}]");
        when(phisClient.detail(any(), eq("A"))).thenReturn(data);
        data.put("htmlContent", "<h2>门诊病历</h2><p>咳嗽3天</p><script>bad()</script><img src='signature'>");

        EmrQcDetailVO detail = service.detail(user, "A", "req");

        assertEquals(1, detail.getSections().size());
        assertEquals("主诉", detail.getSections().get(0).getName());
        assertEquals("咳嗽3天", detail.getSections().get(0).getFields().get(0).getValue());
        assertNotNull(detail.getContentHash());
        org.assertj.core.api.Assertions.assertThat(detail.getHtmlPreview()).contains("<h2>门诊病历</h2>")
            .doesNotContain("bad()", "signature");
        data.put("htmlContent", "<table><tr><td>仅改变预览排版</td></tr></table>");
        EmrQcDetailVO updated = service.detail(user, "A", "req");
        assertEquals(detail.getContentHash(), updated.getContentHash());
        verify(aiClient, never()).analyze(any(), any());
        assertEquals(null, detail.getQc());
        verify(auditService, org.mockito.Mockito.times(2)).record(any(), eq("view_detail"), eq("A"), isNull(), eq("success"));
    }

    @Test
    void invalidIdCardIsRejectedBeforeUpstreamQuery() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.list(user, null, null, null, "123", null, "all", 1, 10));

        assertEquals("EMR-QC-400", exception.getCode());
        verify(phisClient, never()).list(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), anyInt());
    }

    @Test
    void listPassesSelectedOrgDoctorToUpstreamInsteadOfFixedCurrentUser() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        when(directoryService.persons(user, "BBP-ORG-1"))
            .thenReturn(Collections.singletonList(person("D-2", "李四", "1", true)));
        when(phisClient.doctorCandidates(any())).thenReturn(doctorCandidates("D-2", "李四", "BBP-ORG-1"));
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record("A")));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), eq("D-2"), eq("4046"),
            eq(1), eq(10))).thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResponse<EmrQcRecordVO> result = service.list(user, null, null, null, null, "D-2", "all", 1, 10);

        assertEquals(1, result.getTotal());
        verify(phisClient).list(any(), any(), any(), any(), any(), eq("D-2"), eq("4046"),
            eq(1), eq(10));
    }

    @Test
    void listWithoutDepartmentOrDoctorQueriesTheWholeCurrentOrganization() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record("A")));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(1), eq(10)))
            .thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResponse<EmrQcRecordVO> result = service.list(
            user, "2025-10-28", "2026-10-28", null, null, null, "all", 1, 10, null);

        assertEquals(1, result.getTotal());
        verify(phisClient).list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(1), eq(10));
        verify(directoryService).persons(user, "BBP-ORG-1");
    }

    @Test
    void listResolvesDoctorIdentifiersForListAndCachedDetailIncludingInactivePersonnel() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        BbpPersonView doctor = person("USER-1", "李医生", "1", false);
        doctor.setPersonId("PERSON-1");
        when(directoryService.persons(user, "BBP-ORG-1")).thenReturn(Collections.singletonList(doctor));
        PhisEmrRecord record = record("A");
        record.setDoctorName("PERSON-1");
        record.setDoctorUserId("PERSON-1");
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(1), eq(10))).thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        PageResponse<EmrQcRecordVO> result = service.list(user, null, null, null, null, null, "all", 1, 10);

        assertEquals("李医生", result.getRecords().get(0).getDoctorName());
        assertEquals("李医生", recordCache.get("s1", "A").getDoctorName());
        assertEquals("USER-1", recordCache.get("s1", "A").getDoctorUserId());
    }

    @Test
    void ambiguousDoctorIdentifiersAreNotMisidentified() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        BbpPersonView first = person("SAME", "医生甲", "1", true);
        BbpPersonView second = person("SAME", "医生乙", "1", true);
        when(directoryService.persons(user, "BBP-ORG-1")).thenReturn(Arrays.asList(first, second));
        PhisEmrRecord record = record("A");
        record.setDoctorName("SAME");
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(1), eq(10))).thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertEquals("SAME", service.list(user, null, null, null, null, null, "all", 1, 10)
            .getRecords().get(0).getDoctorName());
    }

    @Test
    void unavailablePersonnelDirectoryDoesNotBlockRecordList() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        when(directoryService.persons(user, "BBP-ORG-1"))
            .thenThrow(new ServiceUnavailableException("DIRECTORY", "目录不可用"));
        PhisEmrRecord record = record("A");
        record.setDoctorName("UNKNOWN-ID");
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record));
        page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(1), eq(10))).thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertEquals("UNKNOWN-ID", service.list(user, null, null, null, null, null, "all", 1, 10)
            .getRecords().get(0).getDoctorName());
    }

    @Test
    void listRejectsDateRangeLongerThanOneCalendarYear() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.list(user, "2025-10-28", "2026-10-29", null, null, null, "all", 1, 10));

        assertEquals("EMR-QC-400", exception.getCode());
        assertEquals("查询日期跨度不能超过 1 年", exception.getMessage());
        verify(phisClient, never()).list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unfilteredListPassesRequestedPageAndPreservesUpstreamTotal() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record("A")));
        page.setTotal(17509);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(phisClient.list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(2), eq(20)))
            .thenReturn(page);
        PageResponse<EmrQcRecordVO> result = service.list(user, null, null, null, null, null, "all", 2, 20);
        assertEquals(17509, result.getTotal());
        assertEquals("A", result.getRecords().get(0).getIdMedrecdoc());
        verify(phisClient).list(any(), any(), any(), any(), any(), isNull(), eq("4046"), eq(2), eq(20));
    }

    @Test
    void analyzedListUsesScopedDatabasePageAndAuthorizesOnlyReturnedSnapshots() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        AiEmrQcRecord stored = new AiEmrQcRecord();
        stored.setIdMedrecdoc("QC-A");
        stored.setIdOrg("ORG001");
        stored.setPatientName("张三");
        stored.setGenderCode("1");
        stored.setPatientAge("36");
        stored.setIdCardEncrypted(new com.regionalai.floatingball.server.common.util.AesUtils("test-secret-key").encrypt("330102199001011234"));
        stored.setArchivesEncrypted(new com.regionalai.floatingball.server.common.util.AesUtils("test-secret-key").encrypt("BA0001"));
        stored.setSnapshotComplete(1);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiEmrQcRecord> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiEmrQcRecord>(2, 10, 25);
        page.setRecords(Collections.singletonList(stored));
        when(recordMapper.selectPage(any(), any())).thenReturn(page);

        PageResponse<EmrQcRecordVO> result = service.list(user, "2026-01-01", "2026-10-27", "张", null, null, "analyzed", 2, 10);

        assertEquals(25, result.getTotal());
        assertEquals("张三", result.getRecords().get(0).getPatientName());
        assertEquals(true, result.getRecords().get(0).isSnapshot());
        assertEquals("330102199001011234", recordCache.get("s1", "QC-A").getIdCard());
        assertEquals("BA0001", recordCache.get("s1", "QC-A").getIdArchives());
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> query =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(recordMapper).selectPage(any(), query.capture());
        String sql = query.getValue().getSqlSegment();
        org.assertj.core.api.Assertions.assertThat(sql).contains("id_org", "phis_create_time", "visit_time", "patient_name");
        org.assertj.core.api.Assertions.assertThat(query.getValue().getParamNameValuePairs().values()).contains("ORG001", "%张%");
        verify(phisClient, never()).list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        assertThrows(NotFoundException.class, () -> service.detail(user, "NOT-RETURNED", "req"));
    }

    @Test
    void legacyStatusesAreExplicitlyRejected() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        assertThrows(BusinessException.class,
            () -> service.list(user, null, null, null, null, null, "pending", 1, 10));
    }

    @Test
    void oldResultsAreShownAsIncompleteWithoutGrantingDetailAccess() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        AiEmrQcRecord old = new AiEmrQcRecord();
        old.setIdMedrecdoc("OLD");
        old.setPatientNameMasked("张*");
        old.setIdCardMasked("330****1234");
        old.setSnapshotComplete(0);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiEmrQcRecord> page =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiEmrQcRecord>(1, 10, 1);
        page.setRecords(Collections.singletonList(old));
        when(recordMapper.selectPage(any(), any())).thenReturn(page);

        PageResponse<EmrQcRecordVO> result = service.list(user, null, null, null, null, null, "analyzed", 1, 10);

        assertEquals("张*", result.getRecords().get(0).getPatientName());
        assertEquals(true, result.getRecords().get(0).isSnapshotIncomplete());
        assertThrows(NotFoundException.class, () -> service.detail(user, "OLD", "req"));
    }

    @Test
    void listRejectsDoctorOutsideCurrentOrg() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        when(phisClient.doctorCandidates(any())).thenReturn(doctorCandidates("OTHER", "其他", "OTHER-ORG"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.list(user, null, null, null, null, "OTHER", "all", 1, 10));

        assertEquals("EMR-QC-400", exception.getCode());
        assertEquals("开立医生不属于当前登录机构", exception.getMessage());
        verify(phisClient, never()).list(any(), any(), any(), any(), any(), any(), any(),
            anyInt(), anyInt());
    }

    @Test
    void removedDepartmentFilterIsRejected() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        BusinessException error = assertThrows(BusinessException.class,
            () -> service.list(user, null, null, null, null, null, "all", 1, 10, "门诊内科"));
        assertEquals("EMR-QC-400", error.getCode());
        verify(phisClient, never()).list(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void doctorsUsePhisCandidatesAndExcludeOtherOrganizations() {
        AdminCurrentUser user = user();
        user.setBbpOrgId("BBP-ORG-1");
        when(sessionScope.require(user)).thenReturn(scope());
        com.fasterxml.jackson.databind.node.ArrayNode candidates = doctorCandidates("D-1", "张三", "BBP-ORG-1");
        candidates.addObject().put("userId", "OTHER").put("na", "其他").put("orgId", "OTHER-ORG");
        candidates.addObject().put("userId", "MISSING-ORG").put("na", "未知");
        when(phisClient.doctorCandidates(any())).thenReturn(candidates);

        List<EmrQcDoctorVO> doctors = service.doctors(user);

        assertEquals(1, doctors.size());
        assertEquals("D-1", doctors.get(0).getUserId());
        assertEquals("C-D-1", doctors.get(0).getCode());
    }

    @Test
    void disabledFeatureReturnsStableServiceUnavailableCode() {
        properties.setEnabled(false);

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class,
            () -> service.session(user()));

        assertEquals("EMR-QC-DISABLED", exception.getCode());
    }

    @Test
    void reuseRequiresCurrentRuleVersion() {
        PhisEmrRecord record = record("A");
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setContentHash("hash");
        projection.setPhisUpdateTime(record.getUpdateTime());
        projection.setRuleVersion(EmrQcRubric.RULE_VERSION);

        Boolean current = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "canReuse", projection, record, "hash");
        projection.setRuleVersion("emr-qc-rules-v1");
        Boolean old = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "canReuse", projection, record, "hash");

        assertEquals(Boolean.TRUE, current);
        assertEquals(Boolean.FALSE, old);
        projection.setRuleVersion(EmrQcRubric.RULE_VERSION);
        record.setSnapshot(true);
        assertEquals(Boolean.FALSE, org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "canReuse", projection, record, "hash"));
        Object stale = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            service, "staleState", projection, record, "hash");
        assertEquals("snapshot_unverified", org.springframework.test.util.ReflectionTestUtils.getField(stale, "reason"));
    }

    @Test
    void reviewSavesManualResultAndKeepsAiOriginalScore() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setIdQcRecord("QC-1");
        projection.setScore(82);
        projection.setLatestAnalysisId("AI-1");
        prepareReview(projection);
        projection.setResultSource("ai");
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        AiEmrQcRecord saved = new AiEmrQcRecord();
        saved.setLatestAnalysisId("MANUAL-1");
        saved.setScore(100);
        saved.setResultSource("manual");
        saved.setAiScore(82);
        saved.setReviewedByName("张三");
        saved.setReviewReason("人工复核");
        saved.setFindingCount(0);
        saved.setHighSeverityCount(0);
        when(writer.saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any())).thenReturn(saved);
        when(writer.findFindings(any())).thenReturn(Collections.emptyList());
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setBaseAnalysisId("AI-1");
        request.setReason("人工复核");
        request.setDimensions(fullDimensions());

        EmrQcAnalysisVO result = service.review(user, "A", request, "req");

        assertEquals("manual", result.getResultSource());
        assertEquals(100, result.getScore());
        assertEquals(100, EmrQcRubric.totalWeight());
        assertEquals(82, result.getAiScore());
        assertEquals("张三", result.getReviewedByName());
        verify(writer).saveReview(eq(projection), any(), eq(100), any(), anyList(),
            eq("BBP-USER-1"), eq("张三"), eq("人工复核"));
        verify(auditService).record(any(), eq("manual_review"), eq("A"), isNull(), eq("success"));
    }

    @Test
    void reviewRejectsInvalidDimensionScoreAndMissingReason() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setLatestAnalysisId("AI-1");
        prepareReview(projection);
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setDimensions(fullDimensions());

        BusinessException missingReason = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));
        assertEquals("调整理由不能为空", missingReason.getMessage());

        request.setBaseAnalysisId("AI-1");
        request.setReason("人工复核");
        request.getDimensions().get(0).setScore(11);
        BusinessException outOfRange = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));
        assertEquals("EMR-QC-400", outOfRange.getCode());
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reviewRequiresExistingAiAnalysis() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        when(writer.findRecord("ORG001", "A")).thenReturn(null);
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setBaseAnalysisId("AI-1");
        request.setReason("人工复核");
        request.setDimensions(fullDimensions());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));

        assertEquals("请先完成 AI 质控，再进行人工调整", exception.getMessage());
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reviewNormalizesAndReturnsFindingDeductionAndIgnoreState() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setIdQcRecord("QC-1");
        projection.setLatestAnalysisId("AI-1");
        prepareReview(projection);
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        AiEmrQcRecord saved = new AiEmrQcRecord();
        saved.setLatestAnalysisId("MANUAL-1");
        saved.setScore(100);
        saved.setFindingCount(0);
        saved.setHighSeverityCount(0);
        when(writer.saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any()))
            .thenAnswer(invocation -> {
                List<EmrQcAnalysisVO.Finding> findings = invocation.getArgument(4);
                int requestedScore = invocation.getArgument(2);
                assertEquals(100, requestedScore);
                assertEquals(1, findings.size());
                assertEquals("其他", findings.get(0).getCategory());
                assertEquals(3, findings.get(0).getDeduction());
                assertEquals(Boolean.TRUE, findings.get(0).getIgnored());
                return saved;
            });
        AiEmrQcFinding persisted = new AiEmrQcFinding();
        persisted.setSeverity("high");
        persisted.setCategory("其他");
        persisted.setIssue("急诊生命体征缺失");
        persisted.setDeduction(3);
        persisted.setIgnored(Boolean.TRUE);
        when(writer.findFindings(any())).thenReturn(Collections.singletonList(persisted));
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setBaseAnalysisId("AI-1");
        request.setReason("忽略不适用问题");
        request.setDimensions(fullDimensions());
        request.setFindings(Collections.singletonList(finding("high", "other", 3, true)));

        EmrQcAnalysisVO result = service.review(user, "A", request, "req");

        assertEquals(100, result.getScore());
        assertEquals(0, result.getFindingCount());
        assertEquals(0, result.getHighSeverityCount());
        assertEquals(3, result.getFindings().get(0).getDeduction());
        assertEquals(Boolean.TRUE, result.getFindings().get(0).getIgnored());
    }

    @Test
    void reviewRejectsUnknownFindingCategoryAndOutOfRangeDeductions() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setLatestAnalysisId("AI-1");
        prepareReview(projection);
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setBaseAnalysisId("AI-1");
        request.setReason("人工复核");
        request.setDimensions(fullDimensions());
        EmrQcAnalysisVO.Finding finding = finding("high", "未知分类", 1, false);
        request.setFindings(Collections.singletonList(finding));

        BusinessException unknown = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));
        assertEquals("问题分类必须对应评分维度", unknown.getMessage());

        finding.setCategory("其他");
        finding.setDeduction(6);
        BusinessException outOfRange = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));
        assertEquals("EMR-QC-400", outOfRange.getCode());

        finding.setDeduction(-1);
        BusinessException negative = assertThrows(BusinessException.class,
            () -> service.review(user, "A", request, "req"));
        assertEquals("EMR-QC-400", negative.getCode());
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reviewRejectsOutdatedAnalysisAndChangedRecord() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setLatestAnalysisId("AI-2");
        prepareReview(projection);
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setReason("调整");
        request.setBaseAnalysisId("AI-1");
        assertEquals("EMR-QC-CONFLICT", assertThrows(ConflictException.class,
            () -> service.review(user, "A", request, "req")).getCode());
        request.setBaseAnalysisId("AI-2");
        projection.setContentHash("changed");
        assertEquals("EMR-QC-CONFLICT", assertThrows(ConflictException.class,
            () -> service.review(user, "A", request, "req")).getCode());
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any());
    }

    @Test
    void reviewRejectsDimensionScoresInconsistentWithFindings() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setLatestAnalysisId("AI-1");
        prepareReview(projection);
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        EmrQcReviewRequest request = new EmrQcReviewRequest();
        request.setReason("调整");
        request.setBaseAnalysisId("AI-1");
        request.setDimensions(fullDimensions());
        request.setFindings(Collections.singletonList(finding("high", "other", 3, false)));
        assertThrows(BusinessException.class, () -> service.review(user, "A", request, "req"));
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), anyList(), any(), any(), any());
    }

    @Test
    void scoringCapsDeductionsAndRecalculatesWhenIgnored() {
        List<EmrQcAnalysisVO.Dimension> dimensions = fullDimensions();
        EmrQcAnalysisVO.Finding a = finding("high", "other", 3, false);
        EmrQcAnalysisVO.Finding b = finding("low", "other", 3, false);
        EmrQcRubric.scoreFromFindings(dimensions, Arrays.asList(a, b));
        assertEquals(0, dimensions.get(8).getScore());
        a.setIgnored(true);
        EmrQcRubric.scoreFromFindings(dimensions, Arrays.asList(a, b));
        assertEquals(2, dimensions.get(8).getScore());
    }

    @Test
    void writerCountsOnlyActiveFindingsAndPersistsDeductionAndIgnoreState() {
        AiEmrQcRecordMapper records = org.mockito.Mockito.mock(AiEmrQcRecordMapper.class);
        AiEmrQcFindingMapper findings = org.mockito.Mockito.mock(AiEmrQcFindingMapper.class);
        EmrQcWriter qcWriter = new EmrQcWriter(records, findings);
        AiEmrQcRecord projection = new AiEmrQcRecord();
        projection.setIdQcRecord("QC-1");
        projection.setLatestAnalysisId("AI-1");
        projection.setScore(97);
        projection.setResultSource("ai");

        AiEmrQcRecord saved = qcWriter.saveReview(projection, "MANUAL-1", 95, "[]",
            Arrays.asList(finding("high", "其他", 3, true), finding("medium", "现病史", 2, false)),
            "USER-1", "张三", "人工复核");

        assertEquals(1, saved.getFindingCount());
        assertEquals(0, saved.getHighSeverityCount());
        assertEquals(97, saved.getAiScore());
        assertEquals("AI-1", saved.getAiAnalysisId());
        org.mockito.ArgumentCaptor<AiEmrQcFinding> captor = org.mockito.ArgumentCaptor.forClass(AiEmrQcFinding.class);
        verify(findings, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(3, captor.getAllValues().get(0).getDeduction());
        assertEquals(Boolean.TRUE, captor.getAllValues().get(0).getIgnored());
        assertEquals(2, captor.getAllValues().get(1).getDeduction());
        assertEquals(Boolean.FALSE, captor.getAllValues().get(1).getIgnored());
        verify(records).updateById(projection);
    }

    @Test
    void writerPersistsSnapshotWithoutPlainIdentityNumbers() {
        AiEmrQcRecordMapper records = org.mockito.Mockito.mock(AiEmrQcRecordMapper.class);
        AiEmrQcFindingMapper findings = org.mockito.Mockito.mock(AiEmrQcFindingMapper.class);
        when(records.selectList(any())).thenReturn(Collections.emptyList());
        EmrQcWriter qcWriter = new EmrQcWriter(records, findings);
        com.regionalai.floatingball.server.common.util.AesUtils cipher =
            new com.regionalai.floatingball.server.common.util.AesUtils("test-secret-key");
        EmrQcDraft draft = new EmrQcDraft();
        draft.setIdOrg("ORG001");
        draft.setIdMedrecdoc("A");
        draft.setPatientName("张三");
        draft.setIdCardEncrypted(cipher.encrypt("330102199001011234"));
        draft.setArchivesEncrypted(cipher.encrypt("BA0001"));
        draft.setPhisCreateTime(LocalDateTime.of(2026, 10, 27, 9, 0));

        AiEmrQcRecord saved = qcWriter.save(draft);

        assertEquals(1, saved.getSnapshotComplete());
        assertEquals("张三", saved.getPatientName());
        org.assertj.core.api.Assertions.assertThat(saved.getIdCardEncrypted()).doesNotContain("330102199001011234");
        org.assertj.core.api.Assertions.assertThat(saved.getArchivesEncrypted()).doesNotContain("BA0001");
        assertEquals("330102199001011234", cipher.decrypt(saved.getIdCardEncrypted()));
        verify(records).insert(saved);
    }

    @Test
    void analysisUsesOnlyAiFindingsAndSuppliesMaskedContext() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrRecord record = record("A");
        record.setGenderCode("2");
        recordCache.putAll("s1", Collections.singletonList(record));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("xmlContent", "[{\"ARTICLE\":\"主诉\",\"eles\":[{\"NAME\":\"内容\",\"TEXT\":\"外阴瘙痒1个月\"}]},{\"ARTICLE\":\"婚育史\",\"eles\":[{\"NAME\":\"内容\",\"TEXT\":\"已婚已育\"}]}]");
        when(phisClient.detail(any(), eq("A"))).thenReturn(data);
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setBaseUrl("https://model.invalid");
        config.setModel("test");
        when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result();
        ai.setDimensions(fullDimensions());
        ai.setFindings(Collections.singletonList(finding("medium", "其他", 3, false)));
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord();
        saved.setDimensionsJson("[]");
        when(writer.save(any())).thenReturn(saved);
        when(writer.findFindings(any())).thenReturn(Collections.emptyList());

        service.analyze(user, "A", true, "req");

        org.mockito.ArgumentCaptor<String> input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).analyze(eq(config), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue()).contains("姓名：已提供（已脱敏）", "身份证号：已提供（已脱敏）", "性别：女", "外阴瘙痒1个月", "已婚已育")
            .doesNotContain("张三", "330102199001011234", "BA0001");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).save(draft.capture());
        assertEquals(1, draft.getValue().getFindings().size());
        assertEquals("其他", draft.getValue().getFindings().get(0).getCategory());
        assertEquals(97, draft.getValue().getScore());
        assertEquals(EmrQcRubric.RULE_VERSION, draft.getValue().getRuleVersion());
    }

    private EmrQcAnalysisVO.Finding finding(String severity, String category, int deduction, boolean ignored) {
        EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
        finding.setSeverity(severity);
        finding.setCategory(category);
        finding.setIssue("测试问题");
        finding.setSuggestion("测试建议");
        finding.setDeduction(deduction);
        finding.setIgnored(ignored);
        return finding;
    }

    private List<EmrQcAnalysisVO.Dimension> fullDimensions() {
        List<EmrQcAnalysisVO.Dimension> dimensions = new java.util.ArrayList<EmrQcAnalysisVO.Dimension>();
        for (EmrQcRubric.Spec spec : EmrQcRubric.dimensions()) {
            dimensions.add(EmrQcRubric.dimension(spec.getCode(), spec.getWeight(), null));
        }
        return dimensions;
    }

    private AdminCurrentUser user() {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setAuthSessionId("s1");
        user.setIdOrg("ORG001");
        user.setBbpUserId("BBP-USER-1");
        user.setNaUser("张三");
        user.setRoles(Arrays.asList("SYSTEM_ADMIN"));
        return user;
    }

    private PhisSessionScope.Scope scope() {
        PhisSessionScope.Scope scope = new PhisSessionScope.Scope();
        scope.setAvailable(true);
        scope.setSession(registry.requireSession("s1"));
        scope.setHisOrgId("4046");
        scope.setDeptId("1188");
        scope.setHisOrgName("萧山区第一人民医院");
        scope.setDeptName("门诊内科");
        scope.setDoctorName("张三");
        scope.setRoleName("门诊医生");
        return scope;
    }

    private BbpPersonView person(String userId, String name, String personType, boolean active) {
        BbpPersonView person = new BbpPersonView();
        person.setUserId(userId);
        person.setName(name);
        person.setPersonType(personType);
        person.setCode("CD-" + userId);
        person.setDepartmentId("DEPT-1");
        person.setDepartmentName("门诊内科");
        person.setActive(active);
        return person;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode doctorCandidates(String userId, String name, String orgId) {
        com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
        array.addObject().put("userId", userId).put("na", name).put("orgId", orgId).put("cd", "C-" + userId);
        return array;
    }

    private void prepareReview(AiEmrQcRecord projection) {
        projection.setRuleVersion(EmrQcRubric.RULE_VERSION);
        projection.setPhisUpdateTime(LocalDateTime.of(2026, 10, 24, 9, 30));
        PhisContentNormalizer normalizer = new PhisContentNormalizer(objectMapper);
        String content = "[{\"ARTICLE\":\"主诉\",\"eles\":[{\"NAME\":\"主诉\",\"TEXT\":\"咳嗽\"}]}]";
        projection.setContentHash(normalizer.sha256(normalizer.canonicalText(normalizer.toSections(content))));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("xmlContent", content);
        org.mockito.Mockito.lenient().when(phisClient.detail(any(), eq("A"))).thenReturn(data);
    }

    private PhisEmrRecord record(String id) {
        PhisEmrRecord record = new PhisEmrRecord();
        record.setIdMedrecdoc(id);
        record.setPatientName("张三");
        record.setIdCard("330102199001011234");
        record.setIdArchives("BA0001");
        record.setDoctorName("李四");
        record.setDeptName("门诊内科");
        record.setTemplateName("门诊病历");
        record.setVisitTime(LocalDateTime.of(2026, 10, 24, 9, 12));
        record.setUpdateTime(LocalDateTime.of(2026, 10, 24, 9, 30));
        return record;
    }
}
