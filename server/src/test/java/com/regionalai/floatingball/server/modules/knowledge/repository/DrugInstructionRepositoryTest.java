package com.regionalai.floatingball.server.modules.knowledge.repository;

import com.regionalai.floatingball.server.modules.knowledge.config.DrugInstructionProperties;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionDetail;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DrugInstructionRepositoryTest {
    private DrugInstructionRepository repository;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rs;

    @BeforeEach
    void setup() throws Exception {
        repository = new DrugInstructionRepository(new DrugInstructionProperties());
        HikariDataSource pool = mock(HikariDataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rs = mock(ResultSet.class);
        ReflectionTestUtils.setField(repository, "pool", pool);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
    }

    @Test
    void searchBindsLiteralWildcardsAndNeverLoadsLongSummary() throws Exception {
        String keyword = "%'_! OR 1=1";
        repository.search(keyword, 20, 21);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        assertFalse(sql.getAllValues().get(0).contains(keyword));
        assertTrue(sql.getAllValues().get(0).contains("UNION"));
        assertTrue(sql.getAllValues().get(0).contains("GROUP BY product_id"));
        assertTrue(sql.getAllValues().stream().noneMatch(value -> value.contains("summary")));
        verify(statement).setString(3, "%!%'!_!! OR 1=1%");
        verify(statement).setString(4, keyword);
        verify(statement).setInt(10, 21);
        verify(statement).setInt(11, 20);
        verify(statement, times(2)).setQueryTimeout(5);
        verify(connection).close();
        verify(statement, times(2)).close();
        verify(rs).close();
    }

    @Test
    void detailPreservesFullSummaryAndNormalizesNullText() throws Exception {
        String summary = "【用法用量】\r\n" + String.join("", Collections.nCopies(20000, "合成文本")) + "\r\n【禁忌】\r\n末尾内容";
        when(rs.next()).thenReturn(true);
        when(rs.getString("product_id")).thenReturn("id");
        when(rs.getString("summary")).thenReturn(summary);
        DrugInstructionDetail detail = repository.find("id").get();
        assertEquals(summary, detail.getSummary());
        assertEquals("", detail.getDrugSpec());
        verify(statement).setString(1, "id");
        verify(connection).close();
    }

    @Test
    void unknownIdIsNotAnUpstreamFailure() throws Exception {
        assertFalse(repository.find("missing").isPresent());
    }

    @Test
    void constructingRepositoryNeverInitializesAnExternalPool() {
        DrugInstructionProperties properties = new DrugInstructionProperties();
        properties.setEnabled(true);
        properties.setJdbcUrl("jdbc:mysql://unreachable.invalid/db");
        DrugInstructionRepository lazy = new DrugInstructionRepository(properties);
        assertNull(ReflectionTestUtils.getField(lazy, "pool"));
        lazy.close();
    }
}
