package com.regionalai.floatingball.server.modules.useractivity.mapper;

import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserActivityMapper {

    @SelectProvider(type = UserActivitySqlProvider.class, method = "countActiveDoctors")
    long countActiveDoctors(@Param("query") UserActivityQueryDTO query);

    @SelectProvider(type = UserActivitySqlProvider.class, method = "countTotalDoctors")
    long countTotalDoctors(@Param("query") UserActivityQueryDTO query);

    @SelectProvider(type = UserActivitySqlProvider.class, method = "countEffectiveConsultations")
    long countEffectiveConsultations(@Param("query") UserActivityQueryDTO query);

    @SelectProvider(type = UserActivitySqlProvider.class, method = "countConsultations")
    long countConsultations(@Param("query") UserActivityQueryDTO query);

    @SelectProvider(type = UserActivitySqlProvider.class, method = "queryAllRegions")
    List<Map<String, Object>> queryAllRegions();

    @SelectProvider(type = UserActivitySqlProvider.class, method = "countActiveDoctorsByRegion")
    List<Map<String, Object>> countActiveDoctorsByRegion(@Param("query") UserActivityQueryDTO query);

    @SelectProvider(type = UserActivitySqlProvider.class, method = "queryDoctorActivityList")
    List<Map<String, Object>> queryDoctorActivityList(@Param("query") UserActivityQueryDTO query);
}
