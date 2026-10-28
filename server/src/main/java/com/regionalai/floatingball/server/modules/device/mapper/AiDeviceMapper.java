package com.regionalai.floatingball.server.modules.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiDeviceMapper extends BaseMapper<AiDevice> {

    @Select("SELECT COUNT(1) FROM c_ai_device WHERE cd_device = #{cdDevice} "
        + "AND id_org = #{idOrg} AND fg_active = '0'")
    long countDisabledByCodeAndOrg(@Param("cdDevice") String cdDevice, @Param("idOrg") String idOrg);

    @Delete("DELETE FROM c_ai_device WHERE id_device = #{idDevice}")
    int deleteForResetById(@Param("idDevice") String idDevice);
}
