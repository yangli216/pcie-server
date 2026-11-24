package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmrQcWriterTransactionTest {
    @Test
    void auditFailureRollsBackTheSameWriteTransaction() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        EmrQcWriter writer = transactionalWriter(transactions);
        assertThrows(IllegalStateException.class, () -> writer.saveWithAudit(draft(), () -> {
            throw new IllegalStateException("audit failed");
        }));
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void commitsOnlyAfterResultAndSuccessAuditHaveBothCompleted() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        AtomicBoolean audited = new AtomicBoolean();
        doAnswer(inv -> { assertTrue(audited.get()); return null; }).when(transactions).commit(status);
        transactionalWriter(transactions).saveWithAudit(draft(), () -> audited.set(true));
        verify(transactions).commit(status);
        verify(transactions, never()).rollback(any());
    }

    private EmrQcWriter transactionalWriter(PlatformTransactionManager transactions) {
        AiEmrQcRecordMapper records = mock(AiEmrQcRecordMapper.class);
        when(records.selectList(any())).thenReturn(Collections.emptyList());
        ProxyFactory factory = new ProxyFactory(new EmrQcWriter(records, mock(AiEmrQcFindingMapper.class)));
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (EmrQcWriter) factory.getProxy();
    }

    private EmrQcDraft draft() {
        EmrQcDraft draft = new EmrQcDraft();
        draft.setIdOrg("ORG-FAKE"); draft.setIdMedrecdoc("RECORD-FAKE");
        draft.setFindings(Collections.emptyList());
        return draft;
    }
}
