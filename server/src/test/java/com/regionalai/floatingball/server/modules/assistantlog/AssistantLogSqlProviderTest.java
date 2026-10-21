package com.regionalai.floatingball.server.modules.assistantlog;

import com.regionalai.floatingball.server.modules.assistantlog.dto.AssistantLogQuery;
import com.regionalai.floatingball.server.modules.assistantlog.mapper.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AssistantLogSqlProviderTest {
    @Test
    void bindsFiltersAndPaginationWithBothDatabaseDialectsAndScopesEveryRead() {
        for (boolean pg : new boolean[]{false, true}) {
            Configuration configuration = new Configuration();
            new MapperAnnotationBuilder(configuration, AssistantLogMapper.class).parse();
            AssistantLogQuery query = new AssistantLogQuery();
            query.setDoctorName("%' OR 1=1 --"); query.setIdOrg("org"); query.setHisOrgId("his");
            Map<String, Object> args = new HashMap<>();
            args.put("query", query); args.put("pg", pg); args.put("offset", 10L); args.put("limit", 20L); args.put("idLog", "id");
            for (String method : new String[]{"list", "count", "detail", "organizations", "exportRows"}) {
                BoundSql bound = configuration.getMappedStatement(AssistantLogMapper.class.getName() + "." + method).getBoundSql(args);
                assertThat(bound.getSql()).contains("l.fg_active = '1'", "l.sd_log_type = 'ai_proxy'", "l.na_module = 'ai'", "l.source_module = 'chat_panel'", "l.scene_code IN ('chat-stream', 'chat')").doesNotContain(query.getDoctorName());
                if (method.equals("list")) {
                    assertThat(bound.getSql()).contains(pg ? "LIMIT ? OFFSET ?" : "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
                    assertThat(bound.getSql()).contains(pg ? "::jsonb ->> 'doctorName'" : "JSON_VALUE(");
                    assertThat(bound.getSql()).contains("l.id_org = ?", "l.id_his_org = ?", "l.operation_time < ?");
                    assertThat(bound.getParameterMappings()).hasSize(7);
                }
            }
        }
    }
}
