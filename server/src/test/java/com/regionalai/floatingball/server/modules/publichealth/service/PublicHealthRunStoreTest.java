package com.regionalai.floatingball.server.modules.publichealth.service;

import com.regionalai.floatingball.server.modules.publichealth.entity.*;
import com.regionalai.floatingball.server.modules.publichealth.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicHealthRunStoreTest {

    @org.junit.jupiter.api.BeforeAll static void metadata() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),
        "public-health-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PublicHealthOrigin.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, PublicHealthRun.class);
    }

    @Test void completeReplayNeverReservesAgainAndChangedInputConflicts() throws Exception {
        PublicHealthRunMapper mapper = mock(PublicHealthRunMapper.class);
        PublicHealthRunWriter writer = mock(PublicHealthRunWriter.class);
        PublicHealthRunStore store = new PublicHealthRunStore(mapper, writer, PublicHealthTestFixture.M);
        PublicHealthRun run = run("COMPLETED");
        when(mapper.selectOne(any())).thenReturn(run);
        assertFalse(store.reserve(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription").request,"hash").created);
        verifyNoInteractions(writer);
        assertEquals("REQUEST_ID_CONFLICT", assertThrows(PublicHealthException.class, () -> store.find(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription").request,
        "other")).getCode());
    }

    @Test void runningNeverStartsSecondGenerationAndExpiredBecomesUnknown() throws Exception {
        PublicHealthRunMapper mapper = mock(PublicHealthRunMapper.class);
        PublicHealthRunWriter writer = mock(PublicHealthRunWriter.class);
        PublicHealthRunStore store = new PublicHealthRunStore(mapper, writer, PublicHealthTestFixture.M);
        PublicHealthRun run = run("RUNNING");
        when(mapper.selectOne(any())).thenReturn(run);
        assertEquals("GENERATION_IN_PROGRESS", assertThrows(PublicHealthException.class, () -> store.find(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription").request,
        "hash")).getCode());
        run.setExpiresAtMs(1L);
        PublicHealthRun failed = run("FAILED");
        when(mapper.selectById(any())).thenReturn(failed);
        assertEquals("FAILED", store.find(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription").request,"hash").getStatus());
        verify(writer).finish(eq("generation"), eq("FAILED"), isNull(), eq("GENERATION_STATE_UNKNOWN"), anyString());
    }

    @Test void uniqueKeyRaceReadsWinnerOutsideFailedTransaction() throws Exception {
        PublicHealthRunMapper mapper = mock(PublicHealthRunMapper.class);
        PublicHealthRunWriter writer = mock(PublicHealthRunWriter.class);
        PublicHealthRunStore store = new PublicHealthRunStore(mapper, writer, PublicHealthTestFixture.M);
        when(mapper.selectOne(any())).thenReturn(null, run("COMPLETED"));
        doThrow(new DuplicateKeyException("synthetic race")).when(writer).reserve(any(), any(), anyBoolean());
        assertFalse(store.reserve(PublicHealthTestFixture.device(), PublicHealthTestFixture.parsed("health_prescription").request,"hash").created);
        verify(writer, times(1)).reserve(any(), any(), anyBoolean());
    }

    @Test void immutableOriginAndExplicitSexCannotChange() {
        PublicHealthOriginMapper origins = mock(PublicHealthOriginMapper.class);
        PublicHealthRunMapper runs = mock(PublicHealthRunMapper.class);
        PublicHealthRunWriter writer = new PublicHealthRunWriter(origins, runs);
        PublicHealthOrigin old = origin("same","male"), next = origin("same","female");
        when(origins.selectOne(any())).thenReturn(old);
        assertThrows(PublicHealthException.class, () -> writer.reserve(next, run("RUNNING"), true));
        verify(runs, never()).insert(any());
        next.setPatientSex("male");
        next.setBindingHash("changed version or targets");
        assertThrows(PublicHealthException.class, () -> writer.reserve(next, run("RUNNING"), true));
    }

    @Test void unknownSexNeedsManualSourceAndIsAtomicallyLocked() {
        PublicHealthOriginMapper origins = mock(PublicHealthOriginMapper.class);
        PublicHealthRunMapper runs = mock(PublicHealthRunMapper.class);
        PublicHealthRunWriter writer = new PublicHealthRunWriter(origins, runs);
        PublicHealthOrigin old = origin("same","unknown"), next = origin("same","male");
        when(origins.selectOne(any())).thenReturn(old);
        assertThrows(PublicHealthException.class, () -> writer.reserve(next, run("RUNNING"), false));
        when(origins.update(isNull(), any())).thenReturn(1);
        writer.reserve(next, run("RUNNING"), true);
        verify(runs).insert(any());
    }
    static PublicHealthRun run(String status) {
        PublicHealthRun r = new PublicHealthRun();
        r.setGenerationId("generation");
        r.setInputHash("hash");
        r.setStatus(status);
        r.setExpiresAtMs(System.currentTimeMillis()+180000);
        return r;
    }
    static PublicHealthOrigin origin(String hash, String sex) {
        PublicHealthOrigin o = new PublicHealthOrigin();
        o.setIdOrigin("origin");
        o.setIdOrg("ORG-SYNTHETIC");
        o.setIdDevice("DEVICE-SYNTHETIC");
        o.setOriginRequestId("origin-request");
        o.setBindingHash(hash);
        o.setPatientSex(sex);
        return o;
    }
}
