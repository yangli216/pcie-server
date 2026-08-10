package com.regionalai.floatingball.server.modules.userlog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.userlog.entity.AiUserConsultationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiUserConsultationLogMapper extends BaseMapper<AiUserConsultationLog> {

    @SelectProvider(type = UserConsultationLogSqlProvider.class, method = "selectLatestUserIdentities")
    List<Map<String, Object>> selectLatestUserIdentities(@Param("deviceIds") List<String> deviceIds);
}
