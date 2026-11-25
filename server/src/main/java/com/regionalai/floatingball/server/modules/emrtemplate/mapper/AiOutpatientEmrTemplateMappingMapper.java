package com.regionalai.floatingball.server.modules.emrtemplate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiOutpatientEmrTemplateMappingMapper
    extends BaseMapper<AiOutpatientEmrTemplateMapping> {

    @Delete("DELETE FROM c_ai_outpatient_emr_tpl_mapping WHERE id_snapshot = #{id}")
    int physicalDeleteBySnapshotId(@Param("id") String id);
}
