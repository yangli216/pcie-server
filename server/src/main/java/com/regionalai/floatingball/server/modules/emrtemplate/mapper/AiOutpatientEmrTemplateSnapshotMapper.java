package com.regionalai.floatingball.server.modules.emrtemplate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiOutpatientEmrTemplateSnapshotMapper extends BaseMapper<AiOutpatientEmrTemplateSnapshot> {
    @Select("SELECT id_snapshot FROM c_ai_outpatient_emr_tpl_snapshot WHERE id_snapshot = #{id} FOR UPDATE")
    String lockVersion(@Param("id") String id);

    @Select("SELECT policy_revision FROM c_ai_outpatient_emr_tpl_snapshot WHERE id_snapshot = #{id}")
    Long readRevision(@Param("id") String id);
}
