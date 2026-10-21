package com.regionalai.floatingball.server.modules.inpatientlog;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.*;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.*;
import com.regionalai.floatingball.server.modules.inpatientlog.mapper.*;
import com.regionalai.floatingball.server.modules.inpatientlog.service.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
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
 private InpatientLogQuery query(){InpatientLogQuery q=new InpatientLogQuery();q.setDateFrom("2026-10-01");q.setDateTo("2026-10-31");return q;}
}
