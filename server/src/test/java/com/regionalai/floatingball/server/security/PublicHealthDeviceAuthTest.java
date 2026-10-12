package com.regionalai.floatingball.server.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PublicHealthDeviceAuthTest {
    @Test void publicHealthRequiresPathAndActualBodySignature() throws Exception {
        DeviceService devices=mock(DeviceService.class);ReleaseService releases=mock(ReleaseService.class);KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(256);KeyPair pair=g.generateKeyPair();AiDevice d=new AiDevice();d.setIdDevice("SYNTHETIC-D");d.setIdOrg("SYNTHETIC-O");d.setClientVersion("1.0.0");d.setDevicePublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));when(devices.findActiveByToken("synthetic-token")).thenReturn(d);when(releases.isUpdateRequired(anyString(),anyString())).thenReturn(false);DeviceAuthFilter filter=new DeviceAuthFilter(devices,releases,new RequestSignatureVerifier(),mock(SecurityRejectionLogService.class),new ObjectMapper());
        MockHttpServletResponse rejected=new MockHttpServletResponse();filter.doFilter(request(pair,"/v1/ai/chat"),rejected,new MockFilterChain());assertEquals(401,rejected.getStatus());
        MockHttpServletResponse valid=new MockHttpServletResponse();MockFilterChain chain=new MockFilterChain();filter.doFilter(request(pair,"/v1/public-health/generations"),valid,chain);assertEquals(200,valid.getStatus());assertNotNull(chain.getRequest());assertNull(DeviceContextHolder.get());
    }
    private MockHttpServletRequest request(KeyPair pair,String signedPath) throws Exception {
        String body="{\"synthetic\":true}",timestamp=Long.toString(System.currentTimeMillis()),nonce=UUID.randomUUID().toString();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(pair.getPrivate());s.update(("POST\n"+signedPath+"\n"+timestamp+"\n"+nonce+"\n"+hash+"\n").getBytes(StandardCharsets.UTF_8));MockHttpServletRequest r=new MockHttpServletRequest("POST","/v1/public-health/generations");r.setContent(bytes);r.setContentType("application/json");r.addHeader("Authorization","Bearer synthetic-token");r.addHeader("X-Timestamp",timestamp);r.addHeader("X-Nonce",nonce);r.addHeader("X-Signature",Base64.getEncoder().encodeToString(s.sign()));r.addHeader("X-Body-SHA256",hash.toString());r.addHeader("X-Client-Version","1.0.0");r.addHeader("X-Update-Channel","production");return r;
    }
}
