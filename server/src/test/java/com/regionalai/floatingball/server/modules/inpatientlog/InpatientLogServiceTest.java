package com.regionalai.floatingball.server.modules.inpatientlog;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.*;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.*;
import com.regionalai.floatingball.server.modules.inpatientlog.mapper.*;
import com.regionalai.floatingball.server.modules.inpatientlog.service.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import javax.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class InpatientLogServiceTest {
 private final InpatientLogWriter writer=mock(InpatientLogWriter.class);
 private final InpatientEmrLogMapper logs=mock(InpatientEmrLogMapper.class);
 private final InpatientEmrLogEventMapper events=mock(InpatientEmrLogEventMapper.class);
 private final ObjectMapper json=new ObjectMapper();
 private InpatientLogService service(DatabaseDialect.Kind kind){return new InpatientLogService(writer,logs,events,json,new DatabaseDialect(kind),new BbpStatisticsScope(),Validation.buildDefaultValidatorFactory().getValidator());}
 @BeforeAll static void tables(){for(Class<?> c:new Class<?>[]{InpatientEmrLog.class,InpatientEmrLogEvent.class})TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),c);}
 @AfterEach void clear(){AdminContextHolder.clear();}
 @Test void validatesTypedSnapshotsAndRetriesOnlyInSeparateWriterCallAfterUniqueConflict() {
  InpatientLogService service=service(DatabaseDialect.Kind.OPENGAUSS);InpatientLogSnapshot s=InpatientLogTestData.snapshot();
  when(writer.append(any(),any(),anyString(),anyString())).thenThrow(new DuplicateKeyException("race")).thenReturn(Collections.singletonMap("replayed",true));
  assertThat(service.ingest(InpatientLogTestData.device(),s)).containsEntry("replayed",true);verify(writer,times(2)).append(any(),any(),anyString(),anyString());
  s.setStatus("writeback_success");assertThatThrownBy(()->service.ingest(InpatientLogTestData.device(),s)).hasMessageContaining("不匹配");
  s.setEventType("writeback_success");assertThatThrownBy(()->service.ingest(InpatientLogTestData.device(),s)).hasMessageContaining("请求标识");
  s.setRequestId("request");assertThatThrownBy(()->service.ingest(InpatientLogTestData.device(),s)).hasMessageContaining("最终字段");
  assertThatThrownBy(()->json.readValue("{\"rawHisData\":{}}",InpatientLogSnapshot.class)).hasMessageContaining("日志字段");
 }
 @Test void rejectsDuplicateFieldsAndOversizedContentWithoutWriting() {
  InpatientLogSnapshot s=InpatientLogTestData.snapshot();s.setFields(Arrays.asList(s.getFields().get(0),s.getFields().get(0)));
  assertThatThrownBy(()->service(DatabaseDialect.Kind.ORACLE).ingest(InpatientLogTestData.device(),s)).hasMessageContaining("重复");
  InpatientLogSnapshot invalid=InpatientLogTestData.snapshot();invalid.setEventId("not-a-uuid");
  assertThatThrownBy(()->service(DatabaseDialect.Kind.ORACLE).ingest(InpatientLogTestData.device(),invalid)).hasMessageContaining("格式非法");
  InpatientLogSnapshot oversized=InpatientLogTestData.snapshot();java.util.List<InpatientLogSnapshot.Field> fields=new java.util.ArrayList<>();
  for(int i=0;i<55;i++){InpatientLogSnapshot.Field field=new InpatientLogSnapshot.Field();field.setId("field"+i);field.setName("正文");field.setSource("ai");field.setGeneratedValue(String.join("",Collections.nCopies(20000,"x")));fields.add(field);}
  oversized.setFields(fields);assertThatThrownBy(()->service(DatabaseDialect.Kind.ORACLE).ingest(InpatientLogTestData.device(),oversized)).hasMessageContaining("1MiB");
  verifyNoInteractions(writer);
 }
 @Test void identifiesNestedValidationFailuresWithoutExposingInputValues() {
  InpatientLogSnapshot snapshot = InpatientLogTestData.snapshot();
  snapshot.setEventId("PRIVATE-IDENTIFIER\ninvalid");
  snapshot.setPatientName(String.join("", Collections.nCopies(129, "私")));
  snapshot.getFields().get(0).setSource("PRIVATE-SOURCE\ninvalid");
  snapshot.getFields().get(0).setGeneratedValue("PRIVATE-BODY" + String.join("", Collections.nCopies(20000, "文")));
  InpatientLogSnapshot.Source source = new InpatientLogSnapshot.Source();
  source.setCode("his_summary"); source.setStatus("used");
  source.setText(String.join("", Collections.nCopies(100001, "史")));
  snapshot.setSources(Collections.singletonList(source));
  String expected = "住院日志字段缺失、过长或格式非法：eventId(Pattern); "
   + "fields[0].generatedValue(Size,length=20012,max=20000); fields[0].source(Pattern); "
   + "patientName(Size,length=129,max=128); sources[0].text(Size,length=100001,max=100000)";
  InpatientLogService service = service(DatabaseDialect.Kind.OPENGAUSS);
  for (int i = 0; i < 2; i++) {
   BusinessException error = catchThrowableOfType(() -> service.ingest(InpatientLogTestData.device(), snapshot), BusinessException.class);
   assertThat(error.getCode()).isEqualTo("BIZ-001");
   assertThat(error.getMessage()).isEqualTo(expected).doesNotContain("PRIVATE", "私", "文", "史", "ai|his_or_system");
  }
  verifyNoInteractions(writer, logs, events);
 }
 @Test void boundsValidationDiagnosticsAndReportsRemainingCount() {
  InpatientLogSnapshot snapshot = InpatientLogTestData.snapshot();
  List<InpatientLogSnapshot.Field> fields = new ArrayList<>();
  for (int i = 0; i < 10; i++) {
   InpatientLogSnapshot.Field field = new InpatientLogSnapshot.Field();
   field.setId("field" + i); field.setName("正文"); field.setGeneratedValue(""); field.setSource("PRIVATE-INVALID");
   fields.add(field);
  }
  snapshot.setFields(fields);
  BusinessException error = catchThrowableOfType(() -> service(DatabaseDialect.Kind.ORACLE).ingest(InpatientLogTestData.device(), snapshot), BusinessException.class);
  assertThat(error.getMessage()).contains("fields[0].source(Pattern)", "fields[7].source(Pattern)")
   .endsWith("; 另有2项").doesNotContain("fields[8]", "fields[9]", "PRIVATE-INVALID");
  verifyNoInteractions(writer, logs, events);
 }
 @Test void reportsCollectionCountsAndNumericLimitsWithoutEchoingValues() {
  InpatientLogSnapshot snapshot = InpatientLogTestData.snapshot();
  snapshot.setFields(Collections.nCopies(501, snapshot.getFields().get(0)));
  snapshot.setSequence(0); snapshot.setOccurredAt(Long.MAX_VALUE); snapshot.setSources(null);
  assertThatThrownBy(() -> service(DatabaseDialect.Kind.ORACLE).ingest(InpatientLogTestData.device(), snapshot))
   .hasMessage("住院日志字段缺失、过长或格式非法：fields(Size,count=501,max=500); "
    + "occurredAt(Max,max=4102444800000); sequence(Min,min=1); sources(NotNull)");
  verifyNoInteractions(writer, logs, events);
 }
 @ParameterizedTest @ValueSource(ints = {261, 500})
 void acceptsCompleteLargeTemplatesWithoutDiscardingFields(int count) throws Exception {
  InpatientLogSnapshot snapshot = InpatientLogTestData.snapshot();
  snapshot.setFields(templateFields(count, "完整病程正文"));
  when(writer.append(any(), any(), anyString(), anyString())).thenReturn(Collections.singletonMap("idLog", "accepted"));
  assertThat(service(DatabaseDialect.Kind.OPENGAUSS).ingest(InpatientLogTestData.device(), snapshot)).containsEntry("idLog", "accepted");
  org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
  verify(writer).append(any(), same(snapshot), body.capture(), anyString());
  com.fasterxml.jackson.databind.JsonNode savedFields = json.readTree(body.getValue()).get("fields");
  assertThat(savedFields.size()).isEqualTo(count);
  assertThat(savedFields.get(count - 1).get("id").asText()).isEqualTo("field" + (count - 1));
  assertThat(savedFields.get(count - 1).get("generatedValue").asText()).isEqualTo("完整病程正文");
 }
 @Test void stillRejectsOversizedBodyWhenTemplateFieldCountIsValid() {
  InpatientLogSnapshot snapshot = InpatientLogTestData.snapshot();
  snapshot.setFields(templateFields(261, String.join("", Collections.nCopies(1500, "文"))));
  assertThatThrownBy(() -> service(DatabaseDialect.Kind.OPENGAUSS).ingest(InpatientLogTestData.device(), snapshot))
   .hasMessageContaining("1MiB");
  verifyNoInteractions(writer, logs, events);
 }
 @Test void reportsNullSnapshotWithoutInvokingValidationOrWriting() {
  assertThatThrownBy(() -> service(DatabaseDialect.Kind.OPENGAUSS).ingest(InpatientLogTestData.device(), null))
   .isInstanceOf(BusinessException.class).hasMessage("住院日志字段缺失、过长或格式非法：snapshot(NotNull)");
  verifyNoInteractions(writer, logs, events);
 }
 @Test @SuppressWarnings("unchecked") void bothDialectsBindLiteralNameFiltersAndKeepPayloadOutOfList() {
  for(DatabaseDialect.Kind kind:new DatabaseDialect.Kind[]{DatabaseDialect.Kind.ORACLE,DatabaseDialect.Kind.OPENGAUSS}){
   reset(logs);doAnswer(call->{Page<InpatientEmrLog> p=call.getArgument(0);p.setTotal(1);InpatientEmrLog row=new InpatientEmrLog();row.setIdLog("id");row.setSnapshotJson("SECRET");p.setRecords(Collections.singletonList(row));return p;}).when(logs).selectPage(any(Page.class),any());
   InpatientLogQuery q=query();q.setDoctorName("%' OR 1=1 --");q.setPatientName("患者");
   assertThat(service(kind).list(q,0,999).getSize()).isEqualTo(100);
   org.mockito.ArgumentCaptor<LambdaQueryWrapper> cap=org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);verify(logs).selectPage(any(Page.class),cap.capture());
   assertThat(cap.getValue().getSqlSelect()).doesNotContain("snapshot_json");assertThat(cap.getValue().getSqlSegment()).contains(kind==DatabaseDialect.Kind.ORACLE?"INSTR(doctor_name":"POSITION(").doesNotContain("OR 1=1");
  }
 }
 @Test void scopeIsEnforcedForListOptionsDetailAndExport() {
  AdminCurrentUser user=new AdminCurrentUser();user.setAuthProvider("BBP");user.setRoles(Collections.singletonList("ORG_ADMIN"));user.setBbpOrgId("his-allowed");AdminContextHolder.set(user);
  InpatientLogService service=service(DatabaseDialect.Kind.OPENGAUSS);InpatientLogQuery q=query();q.setHisOrgId("other");
  assertThatThrownBy(()->service.list(q,1,10)).hasMessageContaining("无权");assertThatThrownBy(()->service.exportCsv(q)).hasMessageContaining("无权");
  when(logs.selectList(any())).thenReturn(Collections.emptyList());service.options();
  when(logs.selectOne(any())).thenReturn(null);assertThatThrownBy(()->service.detail("outside")).hasMessageContaining("无权");
  org.mockito.ArgumentCaptor<LambdaQueryWrapper> cap=org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);verify(logs).selectOne(cap.capture());cap.getValue().getSqlSegment();assertThat(cap.getValue().getParamNameValuePairs()).containsValue("his-allowed");
 }
 @Test void rejectsBadDatesAndCapsAndEscapesCsv() {
  InpatientLogService service=service(DatabaseDialect.Kind.ORACLE);InpatientLogQuery q=query();q.setDateTo("2026-02-30");assertThatThrownBy(()->service.exportCsv(q)).hasMessageContaining("日期格式");
  q.setDateFrom("2026-11-01");q.setDateTo("2026-10-01");assertThatThrownBy(()->service.exportCsv(q)).hasMessageContaining("开始日期");
  InpatientEmrLog log=new InpatientEmrLog();log.setOrgName("=SUM(1,2)");log.setStatus("generated");log.setPatientName("引号\"换行\n");when(logs.selectList(any())).thenReturn(Collections.singletonList(log));
  String csv=new String(service.exportCsv(query()),StandardCharsets.UTF_8);assertThat(csv).startsWith("\ufeff").contains("'=SUM(1,2)","引号\"\"换行\n");
  when(logs.selectList(any())).thenReturn(Collections.nCopies(1001,log));assertThatThrownBy(()->service.exportCsv(query())).hasMessageContaining("1000");
 }
 private List<InpatientLogSnapshot.Field> templateFields(int count, String text) {
  List<InpatientLogSnapshot.Field> fields = new ArrayList<>();
  for (int i = 0; i < count; i++) {
   InpatientLogSnapshot.Field field = new InpatientLogSnapshot.Field();
   field.setId("field" + i); field.setName("正文" + i); field.setSource("ai"); field.setGeneratedValue(text);
   fields.add(field);
  }
  return fields;
 }
 private InpatientLogQuery query(){InpatientLogQuery q=new InpatientLogQuery();q.setDateFrom("2026-10-01");q.setDateTo("2026-10-31");return q;}
}
