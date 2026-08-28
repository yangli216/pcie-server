package com.regionalai.floatingball.server.modules.xiaoshananalytics.mapper;

import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface XiaoshanFunctionUsageMapper {

    @SelectProvider(type = XiaoshanFunctionUsageSqlProvider.class, method = "queryRanking")
    List<FunctionUsageItemVO> queryRanking(@Param("query") FunctionUsageQueryDTO query);

    @SelectProvider(type = XiaoshanFunctionUsageSqlProvider.class, method = "queryPreviousRanking")
    List<FunctionUsageItemVO> queryPreviousRanking(@Param("query") FunctionUsageQueryDTO query);

    @SelectProvider(type = XiaoshanFunctionUsageSqlProvider.class, method = "queryTrend")
    List<Map<String, Object>> queryTrend(@Param("query") FunctionUsageQueryDTO query);
}
