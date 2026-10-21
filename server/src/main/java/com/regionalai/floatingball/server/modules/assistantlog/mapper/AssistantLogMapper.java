package com.regionalai.floatingball.server.modules.assistantlog.mapper;

import com.regionalai.floatingball.server.modules.assistantlog.dto.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AssistantLogMapper {
    @SelectProvider(type = AssistantLogSqlProvider.class, method = "count")
    long count(@Param("query") AssistantLogQuery query, @Param("pg") boolean pg);

    @SelectProvider(type = AssistantLogSqlProvider.class, method = "list")
    List<AssistantLogRow> list(@Param("query") AssistantLogQuery query, @Param("pg") boolean pg,
                              @Param("offset") long offset, @Param("limit") long limit);

    @SelectProvider(type = AssistantLogSqlProvider.class, method = "exportRows")
    @Options(fetchSize = 10)
    org.apache.ibatis.cursor.Cursor<AssistantLogRow> exportRows(@Param("query") AssistantLogQuery query, @Param("pg") boolean pg);

    @SelectProvider(type = AssistantLogSqlProvider.class, method = "detail")
    AssistantLogRow detail(@Param("idLog") String idLog);

    @SelectProvider(type = AssistantLogSqlProvider.class, method = "organizations")
    List<AssistantLogOrganization> organizations();
}
