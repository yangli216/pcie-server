package com.regionalai.floatingball.server.modules.inpatientlog.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.InpatientEmrLog;
import org.apache.ibatis.annotations.*;
@Mapper
public interface InpatientEmrLogMapper extends BaseMapper<InpatientEmrLog> {
    @Select("SELECT * FROM c_ai_inpatient_emr_log WHERE id_device = #{deviceId} AND generation_id = #{generationId} FOR UPDATE")
    InpatientEmrLog lockRound(@Param("deviceId") String deviceId, @Param("generationId") String generationId);
}
