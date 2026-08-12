package com.regionalai.floatingball.server.modules.clientusage.mapper;

import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface ClientUsageMapper {

    @SelectProvider(type = ClientUsageSqlProvider.class, method = "countClientUsage")
    long countClientUsage(@Param("query") ClientUsageQueryDTO query);

    @SelectProvider(type = ClientUsageSqlProvider.class, method = "queryClientUsage")
    List<Map<String, Object>> queryClientUsage(@Param("query") ClientUsageQueryDTO query,
                                               @Param("offset") long offset,
                                               @Param("endRow") long endRow);
}
