package com.regionalai.floatingball.server.modules.inpatientlog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.*;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.*;
import com.regionalai.floatingball.server.modules.inpatientlog.mapper.*;
import com.regionalai.floatingball.server.modules.inpatientlog.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class InpatientLogWriterTest {
 private final InpatientEmrLogMapper logs=mock(InpatientEmrLogMapper.class);
 private final InpatientEmrLogEventMapper events=mock(InpatientEmrLogEventMapper.class);
 private final InpatientLogWriter writer=new InpatientLogWriter(logs,events);
 @Test void createsDeviceScopedRoundAndImmutableEvent() {
  InpatientLogSnapshot s=InpatientLogTestData.snapshot();writer.append(InpatientLogTestData.device(),s,"snapshot","hash");
  ArgumentCaptor<InpatientEmrLog> parent=ArgumentCaptor.forClass(InpatientEmrLog.class);verify(logs).insert(parent.capture());
  assertThat(parent.getValue().getIdOrg()).isEqualTo("org");assertThat(parent.getValue().getIdDevice()).isEqualTo("device");assertThat(parent.getValue().getLatestSequence()).isEqualTo(1);
  ArgumentCaptor<InpatientEmrLogEvent> event=ArgumentCaptor.forClass(InpatientEmrLogEvent.class);verify(events).insert(event.capture());
  assertThat(event.getValue().getIdLog()).isEqualTo(parent.getValue().getIdLog());assertThat(event.getValue().getBodyHash()).isEqualTo("hash");
 }
 @Test void sameEventIsIdempotentButChangedContentIsRejected() {
  InpatientEmrLogEvent event=new InpatientEmrLogEvent();event.setIdLog("id");event.setBodyHash("hash");when(events.selectOne(any())).thenReturn(event);
  assertThat(writer.append(InpatientLogTestData.device(),InpatientLogTestData.snapshot(),"snapshot","hash")).containsEntry("replayed",true);
  assertThatThrownBy(()->writer.append(InpatientLogTestData.device(),InpatientLogTestData.snapshot(),"different","other")).hasMessageContaining("其他内容");
  verify(logs,never()).insert(any());verify(events,never()).insert(any());
 }
 @Test void lateEventIsRetainedWithoutRegressingTerminalProjection() {
  InpatientEmrLog parent=parent();parent.setLatestSequence(5);parent.setStatus("writeback_success");when(logs.lockRound(anyString(),anyString())).thenReturn(parent);
  writer.append(InpatientLogTestData.device(),InpatientLogTestData.snapshot(),"old","hash");
  assertThat(parent.getStatus()).isEqualTo("writeback_success");verify(logs,never()).updateById(any());verify(events).insert(any());
 }
 @Test void rejectsCrossPatientAnchorsAndTerminalOrPendingRegression() {
  InpatientEmrLog parent=parent();when(logs.lockRound(anyString(),anyString())).thenReturn(parent);InpatientLogSnapshot s=InpatientLogTestData.snapshot();s.setSequence(3);
  s.setAdmissionId("different");assertThatThrownBy(()->writer.append(InpatientLogTestData.device(),s,"x","hash")).hasMessageContaining("锚点");s.setAdmissionId("admission");
  parent.setStatus("writeback_success");assertThatThrownBy(()->writer.append(InpatientLogTestData.device(),s,"x","hash")).hasMessageContaining("已结束");
  parent.setStatus("writeback_pending");s.setStatus("abandoned");assertThatThrownBy(()->writer.append(InpatientLogTestData.device(),s,"x","hash")).hasMessageContaining("等待");
  verify(events,never()).insert(any());
 }
 private InpatientEmrLog parent(){
  ArgumentCaptor<InpatientEmrLog> capture=ArgumentCaptor.forClass(InpatientEmrLog.class);writer.append(InpatientLogTestData.device(),InpatientLogTestData.snapshot(),"x","hash");verify(logs).insert(capture.capture());reset(logs,events);return capture.getValue();
 }
}
