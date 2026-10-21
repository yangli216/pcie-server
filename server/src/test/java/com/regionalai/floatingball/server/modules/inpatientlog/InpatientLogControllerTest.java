package com.regionalai.floatingball.server.modules.inpatientlog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.inpatientlog.controller.*;
import com.regionalai.floatingball.server.modules.inpatientlog.service.InpatientLogService;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.modules.auth.service.AdminTokenService;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InpatientLogControllerTest {
 private final InpatientLogService service=mock(InpatientLogService.class);
 private final ObjectMapper json=new ObjectMapper();
 private static final String PATH="/v1/client/user-logs/inpatient-emr/events";
 @Test void newClientRouteRequiresRealEcdsaSignatureAndBindsAuthenticatedDevice() throws Exception {
  DeviceService devices=mock(DeviceService.class);KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(256);KeyPair pair=generator.generateKeyPair();
  AiDevice device=InpatientLogTestData.device();device.setDevicePublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));when(devices.findActiveByToken("token")).thenReturn(device);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new ClientInpatientLogController(service)).setControllerAdvice(new GlobalExceptionHandler())
   .addFilters(new DeviceAuthFilter(devices,mock(ReleaseService.class),new RequestSignatureVerifier(),mock(SecurityRejectionLogService.class),json)).build();
  String body=json.writeValueAsString(InpatientLogTestData.snapshot());
  mvc.perform(post(PATH).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  mvc.perform(post(PATH).header("Authorization","Bearer token").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  String timestamp=String.valueOf(System.currentTimeMillis()),nonce=UUID.randomUUID().toString();StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)))hash.append(String.format("%02x",b&255));
  Signature sign=Signature.getInstance("SHA256withECDSA");sign.initSign(pair.getPrivate());sign.update(("POST\n"+PATH+"\n"+timestamp+"\n"+nonce+"\n"+hash+"\n").getBytes(StandardCharsets.UTF_8));
  when(service.ingest(eq(device),any())).thenReturn(Collections.singletonMap("idLog","id"));
  mvc.perform(post(PATH).header("Authorization","Bearer token").header("X-Timestamp",timestamp).header("X-Nonce",nonce)
   .header("X-Signature",Base64.getEncoder().encodeToString(sign.sign())).header("X-Body-SHA256",hash.toString()).contentType("application/json").content(body))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.idLog").value("id"));
  verify(service).ingest(eq(device),any());
 }
 @Test void adminEndpointsRejectAnonymousAndStatisticsOnlyUsers() throws Exception {
  AdminTokenService tokens=mock(AdminTokenService.class);AdminCurrentUser analyst=new AdminCurrentUser();analyst.setAuthProvider("BBP");analyst.setRoles(Collections.singletonList("ORG_ANALYST"));when(tokens.parse("analyst")).thenReturn(analyst);
  MockMvc mvc=MockMvcBuilders.standaloneSetup(new AdminInpatientLogController(service)).addFilters(new AdminAuthFilter(tokens,json,new BbpStatisticsScope())).build();
  for(String suffix:new String[]{"","/options","/export","/id"}){
   mvc.perform(get("/admin/api/inpatient-emr-logs"+suffix)).andExpect(status().isUnauthorized());
   mvc.perform(get("/admin/api/inpatient-emr-logs"+suffix).header("Authorization","Bearer analyst")).andExpect(status().isForbidden());
  }verifyNoInteractions(service);
 }
}
