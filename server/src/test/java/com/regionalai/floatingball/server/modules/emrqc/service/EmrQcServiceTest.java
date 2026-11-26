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
    void detailHashesPreviewTextChangesButNotFormatting() {
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
        data.put("htmlContent", "<h2 style='color:#123'>门诊病历</h2><p>咳嗽3天</p>");
        EmrQcDetailVO updated = service.detail(user, "A", "req");
        assertEquals(detail.getContentHash(), updated.getContentHash());
        data.put("htmlContent", "<h2>门诊病历</h2><p>咳嗽4天</p>");
        EmrQcDetailVO changed = service.detail(user, "A", "req");
        org.assertj.core.api.Assertions.assertThat(changed.getContentHash()).isNotEqualTo(detail.getContentHash());
        verify(aiClient, never()).analyze(any(), any());
        assertEquals(null, detail.getQc());
        verify(auditService, org.mockito.Mockito.times(3)).record(any(), eq("view_detail"), eq("A"), isNull(), eq("success"));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sessionExposesCurrentRuleVersionWithoutDependingOnSessionAvailability(boolean available) {
        AdminCurrentUser user = user();
        PhisSessionScope.Scope scope = scope(); scope.setAvailable(available);
        when(sessionScope.describe(user)).thenReturn(scope);
        com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcSessionVO session = service.session(user);
        assertEquals(available, session.isAvailable());
        assertEquals(EmrQcRubric.RULE_VERSION, session.getRuleVersion());
        org.mockito.Mockito.verifyNoInteractions(aiClient, phisClient);
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
        projection.setRuleVersion("emr-qc-rules-v7");
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
        data.put("htmlContent", "<p>月经史：月经规律，5-6/30天</p><p>姓名：张三</p><script>secret</script>");
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
        when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        when(writer.findFindings(any())).thenReturn(Collections.emptyList());

        service.analyze(user, "A", true, "req");

        org.mockito.ArgumentCaptor<String> input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).analyze(eq(config), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue()).contains("姓名：已提供（已脱敏）", "身份证号：已提供（已脱敏）", "性别：女", "外阴瘙痒1个月", "月经规律，5-6/30天", "已婚已育")
            .doesNotContain("张三", "330102199001011234", "BA0001", "<p>", "secret");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals(1, draft.getValue().getFindings().size());
        assertEquals("其他", draft.getValue().getFindings().get(0).getCategory());
        assertEquals(97, draft.getValue().getScore());
        assertEquals(EmrQcRubric.RULE_VERSION, draft.getValue().getRuleVersion());
    }


    @Test
    void draftFilterIsPassedToPhisAndReturnedSeparatelyFromQualityStatus() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrRecord draft = record("A");
        draft.setRecordStatus("draft");
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(draft)); page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), any(), any(), eq(1), eq(10), eq("draft")))
            .thenReturn(page);
        when(recordMapper.selectList(any())).thenReturn(Collections.emptyList());
        EmrQcRecordVO row = service.list(user, null, null, null, null, null, "all", 1, 10, null, "draft").getRecords().get(0);
        assertEquals("draft", row.getRecordStatus());
        assertEquals("pending", row.getQcStatus());
        assertEquals("A", recordCache.get("s1", "A").getIdMedrecdoc());
    }

    @Test
    void historicalSnapshotsCannotPretendToHaveCurrentRecordStatus() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        assertEquals("EMR-QC-400", assertThrows(BusinessException.class,
            () -> service.list(user, null, null, null, null, null, "analyzed", 1, 10, null, "draft")).getCode());
        verify(recordMapper, never()).selectPage(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"draft", "completed"})
    void sameCurrentContentAndModelResponseKeepsNineDimensionScoreForBothStates(String status) {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrRecord record = record("A"); record.setRecordStatus(status);
        recordCache.putAll("s1", Collections.singletonList(record));
        ObjectNode data = objectMapper.createObjectNode().put("xmlContent", "[{\"ARTICLE\":\"现病史\",\"eles\":[{\"NAME\":\"内容\",\"TEXT\":\"咳嗽三天\"}]}]").put("htmlContent", "<p>主诉：咳嗽三天</p>");
        when(phisClient.detail(any(), eq("A"))).thenReturn(data);
        stubAnalysis(finding("medium", "其他", 3, false));
        service.analyze(user, "A", true, "req");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals(97, draft.getValue().getScore());
        assertEquals(9, objectMapper.valueToTree(fullDimensions()).size());
        assertEquals("emr-qc-rules-v11", draft.getValue().getRuleVersion());
        verify(phisClient, org.mockito.Mockito.times(2)).detail(any(), eq("A"));
    }

    @Test
    void repeatedDraftSavesUseCurrentContentAndReplaceDeduplicatedFindingsAfterRepair() {
        AdminCurrentUser user = user();
        when(sessionScope.require(user)).thenReturn(scope());
        PhisEmrRecord record = record("A"); record.setRecordStatus("draft");
        recordCache.putAll("s1", Collections.singletonList(record));
        ObjectNode content = objectMapper.createObjectNode().put("xmlContent", "[{\"ARTICLE\":\"现病史\",\"eles\":[{\"NAME\":\"内容\",\"TEXT\":\"咳嗽三天\"}]}]").put("htmlContent", "<p>现病史：咳嗽；主诉未填写</p>");
        when(phisClient.detail(any(), eq("A"))).thenAnswer(inv -> content.deepCopy());
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid"); when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        when(aiClient.analyze(eq(config), any())).thenAnswer(inv -> {
            EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions());
            ai.setFindings(inv.<String>getArgument(1).contains("主诉未填写")
                ? Arrays.asList(finding("medium", "主诉", 5, false), finding("medium", "主诉", 5, false))
                : Collections.emptyList()); return ai;
        });
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]");
        when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        service.analyze(user, "A", true, "first");
        content.put("htmlContent", "<p>主诉：咳嗽三天；现病史：咳嗽三天</p>");
        service.analyze(user, "A", true, "repaired");
        org.mockito.ArgumentCaptor<EmrQcDraft> drafts = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer, org.mockito.Mockito.times(2)).saveWithAudit(drafts.capture(), any());
        assertEquals(1, drafts.getAllValues().get(0).getFindings().size());
        assertEquals(95, drafts.getAllValues().get(0).getScore());
        assertEquals(0, drafts.getAllValues().get(1).getFindings().size());
        assertEquals(100, drafts.getAllValues().get(1).getScore());
        org.junit.jupiter.api.Assertions.assertNotEquals(drafts.getAllValues().get(0).getContentHash(), drafts.getAllValues().get(1).getContentHash());
        org.junit.jupiter.api.Assertions.assertNotEquals(drafts.getAllValues().get(0).getAnalysisId(), drafts.getAllValues().get(1).getAnalysisId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void refusesResultsIfContentOrTimestampChangesWhileAiIsRunning(boolean timestampOnly) {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode before = objectMapper.createObjectNode().put("xmlContent", "[{\"ARTICLE\":\"现病史\",\"eles\":[{\"NAME\":\"内容\",\"TEXT\":\"咳嗽三天\"}]}]").put("htmlContent", "<p>主诉：咳嗽三天</p>")
            .put("updateTime", "2026-11-23 10:00:00");
        ObjectNode after = before.deepCopy();
        if (timestampOnly) after.put("updateTime", "2026-11-23 10:01:00");
        else after.put("htmlContent", "<p>主诉：头痛一天</p>");
        when(phisClient.detail(any(), eq("A"))).thenReturn(before, after);
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid"); when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions()); ai.setFindings(Collections.emptyList());
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        assertEquals("EMR-QC-CONFLICT", assertThrows(ConflictException.class,
            () -> service.analyze(user, "A", true, "req")).getCode());
        verify(writer, never()).saveWithAudit(any(), any());
        verify(auditService).record(any(), eq("analyze"), eq("A"), isNull(), eq("failed"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}, batch={1}")
    @org.junit.jupiter.params.provider.MethodSource("chiefComplaintAcceptanceCases")
    void chiefComplaintRulesUseSharedSingleAndBatchChain(String complaint, boolean batch, String history,
                                                         boolean fakeDiagnosis, int expectedNewFindings) {
        AdminCurrentUser user = user();
        PhisEmrRecord record = record("A");
        if (!batch) {
            when(sessionScope.require(user)).thenReturn(scope());
            recordCache.putAll("s1", Collections.singletonList(record));
        }
        com.fasterxml.jackson.databind.node.ArrayNode pages = objectMapper.createArrayNode();
        pages.addObject().put("ARTICLE", "主诉").putArray("eles").addObject().put("NAME", "内容").put("TEXT", complaint);
        pages.addObject().put("ARTICLE", "现病史").putArray("eles").addObject().put("NAME", "内容").put("TEXT", history);
        when(phisClient.detail(any(), eq("A"))).thenReturn(objectMapper.createObjectNode().put("xmlContent", pages.toString()));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid");
        when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        // Fixed clinical decisions exercise the pipeline, not real-model diagnostic accuracy.
        List<EmrQcAnalysisVO.Finding> aiFindings = new java.util.ArrayList<>();
        if (fakeDiagnosis) {
            EmrQcAnalysisVO.Finding diagnosis = finding("low", "主诉", 1, false);
            diagnosis.setRuleCode(EmrQcChiefComplaintRules.DIAGNOSIS);
            diagnosis.setIssue("主诉以诊断名代替症状"); diagnosis.setSuggestion("改为症状描述，如外阴不适1周");
            aiFindings.add(diagnosis);
        }
        if (history.isEmpty()) aiFindings.add(finding("medium", "现病史", 1, false));
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions()); ai.setFindings(aiFindings);
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]");
        when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        if (batch) service.analyzeForTask(new EmrQcContext(user, scope(), "ORG001", "req"), record, true);
        else service.analyze(user, "A", true, "req");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        List<EmrQcAnalysisVO.Finding> findings = draft.getValue().getFindings();
        assertEquals(expectedNewFindings, findings.stream().filter(f -> EmrQcChiefComplaintRules.isRule(f.getRuleCode())).count());
        assertEquals(100 - expectedNewFindings - (history.isEmpty() ? 1 : 0), draft.getValue().getScore());
        assertEquals(EmrQcRubric.RULE_VERSION, draft.getValue().getRuleVersion());
        verify(phisClient, org.mockito.Mockito.times(2)).detail(any(), eq("A"));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> chiefComplaintAcceptanceCases() {
        String twenty = String.join("", Collections.nCopies(15, "咳")) + "，20Ab";
        Object[][] cases = {
            {"反复咳嗽20+天", "反复咳嗽20天", false, 1},
            {twenty + "。！？", "反复咳嗽", false, 1},
            {"阴道炎1周，要求配药", "外阴不适1周", true, 1},
            {"高血压复诊", "无不适，继续配药", false, 0},
            {twenty, "反复咳嗽", false, 0},
            {twenty + "+", "咳嗽伴气促", false, 2},
            {"阴道炎1周", "", true, 0},
            {"外阴不适1周", "外阴不适1周", false, 0},
            {"咳嗽+发热3天", "咳嗽发热3天", false, 1},
            // Requirement example 10 is exactly 20 characters despite its stated >20 expectation.
            {"咳嗽咳痰伴气促反复发作20余天，门诊治疗", "咳嗽伴气促", false, 0},
            {"咳嗽咳痰伴气促反复发作20余天，门诊治疗后", "咳嗽伴气促", false, 1},
            {"阴道炎反复发作伴不适要求配药治疗20+天，随访", "外阴不适20天", true, 3},
            {"阴道炎伴外阴不适1周", "外阴不适1周", false, 0},
            {"上呼吸道感染3天", "咳嗽咽痛3天", true, 1}
        };
        return java.util.Arrays.stream(cases).flatMap(c -> java.util.stream.Stream.of(false, true)
            .map(batch -> org.junit.jupiter.params.provider.Arguments.of(c[0], batch, c[1], c[2], c[3])));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "allergy={0}, batch={1}")
    @org.junit.jupiter.params.provider.MethodSource("allergyAcceptanceCases")
    void allergyEvidenceAndScoringUseSingleAndBatchChain(String text, boolean batch, String section,
                                                        String fieldName, String code, int deduction, int expected) {
        AdminCurrentUser user = user(); PhisEmrRecord record = record("A");
        if (!batch) {
            when(sessionScope.require(user)).thenReturn(scope());
            recordCache.putAll("s1", Collections.singletonList(record));
        }
        com.fasterxml.jackson.databind.node.ArrayNode pages = objectMapper.createArrayNode();
        pages.addObject().put("ARTICLE", "主诉").putArray("eles").addObject().put("NAME", "主诉").put("TEXT", "咳嗽3天");
        pages.addObject().put("ARTICLE", section).putArray("eles").addObject().put("NAME", fieldName).put("TEXT", text);
        when(phisClient.detail(any(), eq("A"))).thenReturn(objectMapper.createObjectNode().put("xmlContent", pages.toString()));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid");
        when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions());
        if (code == null) ai.setFindings(Collections.emptyList());
        else {
            EmrQcAnalysisVO.Finding finding = finding("low", "患者基础信息", deduction, false);
            finding.setRuleCode(code); finding.setSectionName("既往史"); finding.setSuggestion("补充所缺类别的过敏情况，保留已有阳性过敏记录");
            ai.setFindings(Collections.singletonList(finding));
        }
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]"); when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        if (batch) service.analyzeForTask(new EmrQcContext(user, scope(), "ORG001", "req"), record, true);
        else service.analyze(user, "A", true, "req");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals(100 - expected, draft.getValue().getScore());
        assertEquals(expected == 0 ? 0 : 1, draft.getValue().getFindings().size());
        org.mockito.ArgumentCaptor<String> input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).analyze(eq(config), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue()).contains("【过敏史固定字段证据】")
            .doesNotContain("张三", "13800138000", "<");
        if ("过敏史".equals(fieldName) && "既往史".equals(section)) {
            org.assertj.core.api.Assertions.assertThat(input.getValue()).contains(org.springframework.util.StringUtils.hasText(text) ? "字段状态：已填写" : "字段状态：明确为空");
        } else org.assertj.core.api.Assertions.assertThat(input.getValue()).contains("字段状态：未识别");
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> allergyAcceptanceCases() {
        Object[][] cases = {
            {"", "既往史", "过敏史", EmrQcAllergyRules.MISSING, 5, 5},
            {"否认过敏史", "既往史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 1},
            {"否认药物及食物过敏史", "既往史", "过敏史", null, 0, 0},
            {"否认食品、药物过敏史", "既往史", "过敏史", null, 0, 0},
            {"青霉素过敏", "既往史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 1},
            {"否认药物过敏史", "既往史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 1},
            {"否认家族史", "家族史", "内容", EmrQcAllergyRules.INCOMPLETE, 1, 0},
            {"海鲜过敏", "既往史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 1},
            {"青霉素及海鲜过敏", "既往史", "过敏史", null, 0, 0},
            {"青霉素过敏，否认食物过敏史", "既往史", "过敏史", null, 0, 0},
            {" \n ", "既往史", "过敏史", EmrQcAllergyRules.MISSING, 5, 5},
            {"否认过敏史", "家族史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 0},
            {"健康", "个人史", "内容", EmrQcAllergyRules.MISSING, 5, 0},
            {"张三青霉素过敏，电话13800138000", "既往史", "过敏史", EmrQcAllergyRules.INCOMPLETE, 1, 1}
        };
        // Clinical decisions are explicit Fake inputs; these cases verify evidence routing and scoring only.
        return java.util.Arrays.stream(cases).flatMap(c -> java.util.stream.Stream.of(false, true)
            .map(batch -> org.junit.jupiter.params.provider.Arguments.of(c[0], batch, c[1], c[2], c[3], c[4], c[5])));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "aux={0}, batch={1}")
    @org.junit.jupiter.params.provider.MethodSource("auxiliaryAcceptanceCases")
    void auxiliaryRuleUsesSharedSingleAndBatchChain(String projects, boolean batch, String results, boolean fakeMissing) {
        AdminCurrentUser user = user(); PhisEmrRecord record = record("A");
        if (!batch) {
            when(sessionScope.require(user)).thenReturn(scope());
            recordCache.putAll("s1", Collections.singletonList(record));
        }
        com.fasterxml.jackson.databind.node.ArrayNode pages = objectMapper.createArrayNode();
        pages.addObject().put("ARTICLE", "主诉").putArray("eles").addObject().put("NAME", "主诉").put("TEXT", "咳嗽3天");
        pages.addObject().put("ARTICLE", "诊疗措施").putArray("eles").addObject().put("NAME", "项目与计划").put("TEXT", projects);
        pages.addObject().put("ARTICLE", "辅助检查").putArray("eles").addObject().put("NAME", "检查结果").put("TEXT", results);
        when(phisClient.detail(any(), eq("A"))).thenReturn(objectMapper.createObjectNode().put("xmlContent", pages.toString()));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid"); when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions());
        if (fakeMissing) {
            EmrQcAnalysisVO.Finding finding = finding("low", "查体与辅助检查", 5, false);
            finding.setRuleCode(EmrQcRubric.AUX_RESULT_MISSING); finding.setIssue("辅助检查有项目但无实际结果"); finding.setSuggestion("补充病历中的检验检查结果");
            EmrQcAnalysisVO.Finding duplicate = finding("low", "查体与辅助检查", 5, false);
            duplicate.setRuleCode(EmrQcRubric.AUX_RESULT_MISSING); duplicate.setIssue("同一缺结果问题的另一种描述");
            ai.setFindings(Arrays.asList(finding, duplicate));
        } else ai.setFindings(Collections.emptyList());
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]"); when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        if (batch) service.analyzeForTask(new EmrQcContext(user, scope(), "ORG001", "req"), record, true);
        else service.analyze(user, "A", true, "req");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals(fakeMissing ? 95 : 100, draft.getValue().getScore());
        assertEquals(fakeMissing ? 1 : 0, draft.getValue().getFindings().size());
        org.mockito.ArgumentCaptor<String> input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).analyze(eq(config), input.capture());
        if (!projects.isEmpty()) org.assertj.core.api.Assertions.assertThat(input.getValue()).contains(projects);
        if (!results.isEmpty()) org.assertj.core.api.Assertions.assertThat(input.getValue()).contains(results);
        verify(phisClient, org.mockito.Mockito.times(2)).detail(any(), eq("A"));
        org.mockito.Mockito.verifyNoMoreInteractions(phisClient);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> auxiliaryAcceptanceCases() {
        Object[][] cases = {
            {"建卡已开血常规、尿常规、肝功能、肾功能、血型、HIV、梅毒、乙肝、丙肝、血糖、心电图、B超、凝血功能共13项", "", true},
            {"已开血常规", "导入：WBC 6.2×10^9/L", false},
            {"已开血常规", "医生手填：WBC 6.2×10^9/L", false},
            {"已开血常规", "血常规", true},
            {"外送HIV、梅毒、血型", "", true},
            {"", "", false},
            {"已开血常规、肝功能", "血常规正常、肝功能未见异常", false},
            {"已开血常规、肝肾功能", "血常规、肝肾功能未见异常", false},
            {"已开血常规", "血常规待回报", true},
            {"已开血常规", "未回报", true},
            {"若症状持续，建议进一步复查血常规", "", false},
            {"门诊换药", "", false},
            {"当日已开甲状腺功能，预计次日出结果", "待回报", true}
        };
        // Explicit Fake decisions verify the supplied clinical context and the common scoring path.
        return java.util.Arrays.stream(cases).flatMap(c -> java.util.stream.Stream.of(false, true)
            .map(batch -> org.junit.jupiter.params.provider.Arguments.of(c[0], batch, c[1], c[2])));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void integratedChiefAllergyAndAuxiliaryRulesUseTheSameScoringChain(boolean batch) {
        AdminCurrentUser user = user(); PhisEmrRecord record = record("A");
        if (!batch) {
            when(sessionScope.require(user)).thenReturn(scope());
            recordCache.putAll("s1", Collections.singletonList(record));
        }
        com.fasterxml.jackson.databind.node.ArrayNode pages = objectMapper.createArrayNode();
        pages.addObject().put("ARTICLE", "主诉").putArray("eles").addObject().put("NAME", "主诉").put("TEXT", "反复咳嗽20+天");
        pages.addObject().put("ARTICLE", "既往史").putArray("eles").addObject().put("NAME", "过敏史").put("TEXT", "否认过敏史");
        pages.addObject().put("ARTICLE", "辅助检查").putArray("eles").addObject().put("NAME", "结果").put("TEXT", "血常规待回报");
        when(phisClient.detail(any(), eq("A"))).thenReturn(objectMapper.createObjectNode().put("xmlContent", pages.toString()));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid");
        when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAnalysisVO.Finding allergy = finding("low", "患者基础信息", 1, false); allergy.setRuleCode(EmrQcAllergyRules.INCOMPLETE);
        EmrQcAnalysisVO.Finding auxiliary = finding("low", "查体与辅助检查", 5, false); auxiliary.setRuleCode(EmrQcRubric.AUX_RESULT_MISSING);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions()); ai.setFindings(Arrays.asList(allergy, auxiliary));
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]"); when(writer.saveWithAudit(any(), any())).thenReturn(saved);
        if (batch) service.analyzeForTask(new EmrQcContext(user, scope(), "ORG001", "req"), record, true);
        else service.analyze(user, "A", true, "req");
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals(93, draft.getValue().getScore());
        org.assertj.core.api.Assertions.assertThat(draft.getValue().getFindings()).extracting(EmrQcAnalysisVO.Finding::getRuleCode)
            .containsExactlyInAnyOrder(EmrQcChiefComplaintRules.PLUS, EmrQcAllergyRules.INCOMPLETE, EmrQcRubric.AUX_RESULT_MISSING);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void proxyRefillSkipsAllRulesAndAiForSingleBatchAndBothContentSources(boolean batch, boolean preview) throws Exception {
        AdminCurrentUser user = user();
        PhisEmrRecord record = record("A");
        if (!batch) {
            when(sessionScope.require(user)).thenReturn(scope());
            recordCache.putAll("s1", Collections.singletonList(record));
        }
        ObjectNode data = proxyContent("家长代配药＋咳嗽伴发热流涕咳痰已经三天且要求续配药物");
        if (!preview) data.remove("htmlContent");
        when(phisClient.detail(any(), eq("A"))).thenReturn(data);
        persistDrafts();

        EmrQcAnalysisVO result = batch
            ? service.analyzeForTask(new EmrQcContext(user, scope(), "ORG001", "req"), record, true)
            : service.analyze(user, "A", true, "req");

        assertEquals(100, result.getScore());
        assertEquals(9, result.getDimensions().size());
        for (EmrQcAnalysisVO.Dimension dimension : result.getDimensions()) assertEquals(dimension.getWeight(), dimension.getScore());
        assertEquals(0, result.getFindingCount());
        assertEquals(0, result.getHighSeverityCount());
        org.junit.jupiter.api.Assertions.assertTrue(result.getFindings().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(result.isProxyRefill());
        org.junit.jupiter.api.Assertions.assertNull(result.getModel());
        org.junit.jupiter.api.Assertions.assertNull(result.getAiScore());
        assertEquals("emr-qc-rules-v11", result.getRuleVersion());
        org.mockito.Mockito.verifyNoInteractions(configService, aiClient);
        verify(phisClient, org.mockito.Mockito.times(2)).detail(any(), eq("A"));
        verify(auditService).recordRequired(any(), eq(batch ? "batch_analyze" : "analyze"), eq("A"), isNull(), eq("proxy_refill"));
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        org.junit.jupiter.api.Assertions.assertNull(draft.getValue().getModel());
        assertEquals("proxy_refill", draft.getValue().getResultSource());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"双歧杆菌三联活菌散", "无代配药", "非代配药", "代开药", "代取药", "要求配药", "咳嗽三天"})
    void absentOrNonAffirmativeChiefComplaintDoesNotExemptOtherSectionsOrMedication(String complaint) {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode data = objectMapper.createObjectNode();
        data.put("htmlContent", (complaint == null ? "" : "<p>主诉：" + complaint + "</p>")
            + "<p>现病史：家长代配药</p><p>处理：双歧杆菌三联活菌散</p>");
        com.fasterxml.jackson.databind.node.ArrayNode sections = objectMapper.createArrayNode();
        if (complaint != null) sections.addObject().put("ARTICLE", "主诉").putArray("eles")
            .addObject().put("NAME", "主诉").put("TEXT", complaint);
        sections.addObject().put("ARTICLE", "现病史").putArray("eles").addObject().put("TEXT", "家长代配药");
        sections.addObject().put("ARTICLE", "处理").putArray("eles").addObject().put("TEXT", "双歧杆菌三联活菌散");
        data.put("xmlContent", sections.toString());
        when(phisClient.detail(any(), eq("A"))).thenReturn(data);
        stubAnalysis(finding("medium", "现病史", 10, false));
        service.analyze(user, "A", true, "req");
        verify(aiClient).analyze(any(), any());
        org.mockito.ArgumentCaptor<EmrQcDraft> draft = org.mockito.ArgumentCaptor.forClass(EmrQcDraft.class);
        verify(writer).saveWithAudit(draft.capture(), any());
        assertEquals("ai", draft.getValue().getResultSource());
        assertEquals(90, draft.getValue().getScore());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void proxyRefillStillRejectsWriteTimeBodyOrTimestampChanges(boolean timestampOnly) {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode before = proxyContent("家长代配药");
        ObjectNode after = timestampOnly ? before.deepCopy().put("updateTime", "2026-10-24 09:31:00") : proxyContent("咳嗽三天");
        when(phisClient.detail(any(), eq("A"))).thenReturn(before, after);
        assertEquals("EMR-QC-CONFLICT", assertThrows(ConflictException.class,
            () -> service.analyze(user, "A", true, "req")).getCode());
        verify(writer, never()).saveWithAudit(any(), any());
        org.mockito.Mockito.verifyNoInteractions(configService, aiClient);
    }

    @Test
    void currentProxyResultCanBeReusedButChangingChiefComplaintRestoresNormalQc() {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode data = proxyContent("家长代配药");
        when(phisClient.detail(any(), eq("A"))).thenAnswer(inv -> data.deepCopy());
        java.util.concurrent.atomic.AtomicReference<AiEmrQcRecord> stored = persistDrafts();
        when(writer.findRecord("ORG001", "A")).thenAnswer(inv -> stored.get());
        EmrQcAnalysisVO first = service.analyze(user, "A", true, "first");
        EmrQcAnalysisVO reused = service.analyze(user, "A", false, "reuse");
        org.junit.jupiter.api.Assertions.assertTrue(reused.getReused());
        assertEquals(first.getAnalysisId(), reused.getAnalysisId());
        stored.get().setRuleVersion("emr-qc-rules-v10");
        EmrQcAnalysisVO updated = service.analyze(user, "A", false, "version");
        org.junit.jupiter.api.Assertions.assertFalse(updated.getReused());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.getAnalysisId(), updated.getAnalysisId());
        org.mockito.Mockito.verifyNoInteractions(configService, aiClient);

        data.setAll(proxyContent("咳嗽三天"));
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid");
        when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions());
        ai.setFindings(Collections.singletonList(finding("medium", "现病史", 10, false)));
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        EmrQcAnalysisVO normal = service.analyze(user, "A", false, "changed");
        org.junit.jupiter.api.Assertions.assertFalse(normal.isProxyRefill());
        assertEquals("ai", normal.getResultSource());
        assertEquals(90, normal.getScore());
        verify(aiClient).analyze(eq(config), any());
    }

    @Test
    void proxyMarkFlowsToListAndDetailAndBecomesHistoricalOnContentChange() {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode data = proxyContent("家长代配药");
        when(phisClient.detail(any(), eq("A"))).thenAnswer(inv -> data.deepCopy());
        java.util.concurrent.atomic.AtomicReference<AiEmrQcRecord> stored = persistDrafts();
        when(writer.findRecord("ORG001", "A")).thenAnswer(inv -> stored.get());
        service.analyze(user, "A", true, "req");
        stored.get().setIdMedrecdoc("A");
        when(recordMapper.selectList(any())).thenAnswer(inv -> Collections.singletonList(stored.get()));
        PhisEmrClient.ListPage page = new PhisEmrClient.ListPage();
        page.setRecords(Collections.singletonList(record("A"))); page.setTotal(1);
        when(phisClient.list(any(), any(), any(), any(), any(), any(), any(), eq(1), eq(10))).thenReturn(page);
        EmrQcRecordVO row = service.list(user, null, null, null, null, null, "all", 1, 10).getRecords().get(0);
        org.junit.jupiter.api.Assertions.assertTrue(row.isProxyRefill());
        assertEquals(100, row.getScore());
        EmrQcDetailVO detail = service.detail(user, "A", "detail");
        org.junit.jupiter.api.Assertions.assertTrue(detail.getQc().isProxyRefill());
        org.junit.jupiter.api.Assertions.assertFalse(detail.getQc().isStale());
        data.setAll(proxyContent("咳嗽三天"));
        EmrQcDetailVO changed = service.detail(user, "A", "changed");
        org.junit.jupiter.api.Assertions.assertTrue(changed.getQc().isProxyRefill());
        org.junit.jupiter.api.Assertions.assertTrue(changed.getQc().isStale());
        assertEquals("content_changed", changed.getQc().getStaleReason());
    }

    @Test
    void proxyResultCannotBeManuallyDeducted() {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        AiEmrQcRecord projection = new AiEmrQcRecord(); projection.setResultSource("proxy_refill");
        when(writer.findRecord("ORG001", "A")).thenReturn(projection);
        assertEquals("EMR-QC-CONFLICT", assertThrows(ConflictException.class,
            () -> service.review(user, "A", new EmrQcReviewRequest(), "req")).getCode());
        verify(writer, never()).saveReview(any(), any(), anyInt(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(aiClient, configService);
    }

    @Test
    void cachedProxyIdentityNeverBypassesAnExpiredSession() {
        AdminCurrentUser user = user();
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        when(sessionScope.require(user)).thenThrow(new ConflictException("EMR-PHIS-SESSION", "会话已失效"));
        assertEquals("EMR-PHIS-SESSION", assertThrows(ConflictException.class,
            () -> service.analyze(user, "A", true, "req")).getCode());
        org.mockito.Mockito.verifyNoInteractions(phisClient, writer, aiClient, configService);
    }

    @Test
    void cancellingProxyMarkerCannotReuseItsScoreWhenNormalAiConfigurationIsMissing() {
        AdminCurrentUser user = user(); when(sessionScope.require(user)).thenReturn(scope());
        recordCache.putAll("s1", Collections.singletonList(record("A")));
        ObjectNode content = proxyContent("家长代配药");
        when(phisClient.detail(any(), eq("A"))).thenAnswer(inv -> content.deepCopy());
        java.util.concurrent.atomic.AtomicReference<AiEmrQcRecord> stored = persistDrafts();
        when(writer.findRecord("ORG001", "A")).thenAnswer(inv -> stored.get());
        service.analyze(user, "A", true, "first");
        content.setAll(proxyContent("咳嗽三天"));
        assertEquals("EMR-QC-UNAVAILABLE", assertThrows(ServiceUnavailableException.class,
            () -> service.analyze(user, "A", false, "changed")).getCode());
        verify(writer, org.mockito.Mockito.times(1)).saveWithAudit(any(), any());
        org.mockito.Mockito.verifyNoInteractions(aiClient);
        verify(auditService).record(any(), eq("analyze"), eq("A"), isNull(), eq("failed"));
    }

    private ObjectNode proxyContent(String complaint) {
        ObjectNode data = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode sections = objectMapper.createArrayNode();
        sections.addObject().put("ARTICLE", "主诉").putArray("eles").addObject().put("NAME", "主诉").put("TEXT", complaint);
        sections.addObject().put("ARTICLE", "现病史").putArray("eles").addObject().put("TEXT", "");
        data.put("xmlContent", sections.toString());
        data.put("htmlContent", "<p>主诉：" + complaint + "</p><p>现病史：</p><p>过敏史：否认过敏史</p><p>辅助检查：血常规待回报</p>");
        data.put("updateTime", "2026-10-24 09:30:00");
        return data;
    }

    /** Fake persistence only: checks service behavior, not database SQL or transaction durability. */
    private java.util.concurrent.atomic.AtomicReference<AiEmrQcRecord> persistDrafts() {
        java.util.concurrent.atomic.AtomicReference<AiEmrQcRecord> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(writer.saveWithAudit(any(), any())).thenAnswer(inv -> {
            EmrQcDraft draft = inv.getArgument(0);
            inv.<Runnable>getArgument(1).run();
            AiEmrQcRecord entity = new AiEmrQcRecord();
            entity.setLatestAnalysisId(draft.getAnalysisId()); entity.setScore(draft.getScore());
            entity.setDimensionsJson(draft.getDimensionsJson()); entity.setFindingCount(draft.getFindings().size());
            entity.setHighSeverityCount(0); entity.setContentHash(draft.getContentHash());
            entity.setPhisUpdateTime(draft.getPhisUpdateTime()); entity.setRuleVersion(draft.getRuleVersion());
            entity.setResultSource(draft.getResultSource()); entity.setModel(draft.getModel());
            stored.set(entity); return entity;
        });
        return stored;
    }

    private void stubAnalysis(EmrQcAnalysisVO.Finding finding) {
        com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig config = new com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig();
        config.setModel("fake"); config.setBaseUrl("https://model.invalid"); when(configService.resolveByScope(eq("ORG001"), isNull())).thenReturn(config);
        EmrQcAiClient.Result ai = new EmrQcAiClient.Result(); ai.setDimensions(fullDimensions()); ai.setFindings(Collections.singletonList(finding));
        when(aiClient.analyze(eq(config), any())).thenReturn(ai);
        AiEmrQcRecord saved = new AiEmrQcRecord(); saved.setDimensionsJson("[]");
        when(writer.saveWithAudit(any(), any())).thenReturn(saved);
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
        projection.setContentHash(normalizer.sha256(normalizer.canonicalText(normalizer.toSections(content))
            + "\n【主诉规则输入摘要】\n" + normalizer.sha256("咳嗽")
            + "\n【过敏史字段证据摘要】\n" + normalizer.sha256("unknown")));
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
