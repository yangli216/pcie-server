package com.regionalai.floatingball.server.modules.analytics.mapper;

import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.HisOrgOptionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryFinalizedChangeSummaries")
    @Options(fetchSize = 100, resultSetType = ResultSetType.FORWARD_ONLY)
    @ResultType(String.class)
    void visitFinalizedChangeSummaries(@Param("query") AnalyticsQueryDTO query, ResultHandler<String> handler);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countAiService")
    long countAiService(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countConsultation")
    long countConsultation(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countActiveDoctors")
    long countActiveDoctors(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countAdoptedConsultations")
    long countAdoptedConsultations(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countFinalizedConsultations")
    long countFinalizedConsultations(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "countDiagnosisMatchedConsultations")
    long countDiagnosisMatchedConsultations(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryAiServiceTrend")
    List<Map<String, Object>> queryAiServiceTrend(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryConsultationTrend")
    List<Map<String, Object>> queryConsultationTrend(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryOrgDistribution")
    List<DistributionItemVO> queryOrgDistribution(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryRegionDistributionRaw")
    List<DistributionItemVO> queryRegionDistributionRaw(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryConsultationOrgDistribution")
    List<DistributionItemVO> queryConsultationOrgDistribution(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryConsultationRegionDistributionRaw")
    List<DistributionItemVO> queryConsultationRegionDistributionRaw(@Param("query") AnalyticsQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryHisOrgOptions")
    List<HisOrgOptionVO> queryHisOrgOptions();

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryDistinctModules")
    List<String> queryDistinctModules();

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryFunctionUsageRanking")
    List<FunctionUsageItemVO> queryFunctionUsageRanking(@Param("query") FunctionUsageQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryFunctionUsagePreviousRanking")
    List<FunctionUsageItemVO> queryFunctionUsagePreviousRanking(@Param("query") FunctionUsageQueryDTO query);

    @SelectProvider(type = AnalyticsSqlProvider.class, method = "queryFunctionUsageTrend")
    List<Map<String, Object>> queryFunctionUsageTrend(@Param("query") FunctionUsageQueryDTO query);
}
