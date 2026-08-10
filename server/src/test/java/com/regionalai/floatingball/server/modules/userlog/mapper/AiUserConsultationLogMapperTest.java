package com.regionalai.floatingball.server.modules.userlog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.userlog.entity.AiUserConsultationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUserConsultationLogMapperTest {

    @Test
    void mapperShouldKeepLatestUserIdentityQueryContract() throws Exception {
        assertTrue(AiUserConsultationLogMapper.class.isAnnotationPresent(Mapper.class));

        Method method = AiUserConsultationLogMapper.class.getMethod("selectLatestUserIdentities", List.class);
        SelectProvider provider = method.getAnnotation(SelectProvider.class);
        assertEquals(UserConsultationLogSqlProvider.class, provider.type());
        assertEquals("selectLatestUserIdentities", provider.method());

        Parameter parameter = method.getParameters()[0];
        assertEquals("deviceIds", parameter.getAnnotation(Param.class).value());
    }

    @Test
    void providerShouldSelectLatestNonBlankDoctorIdentityForEachDevice() {
        String sql = new UserConsultationLogSqlProvider().selectLatestUserIdentities();

        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY ucl.id_device"));
        assertTrue(sql.contains("ORDER BY ucl.consultation_time DESC, ucl.id_log DESC"));
        assertTrue(sql.contains("LENGTH(TRIM(ucl.na_doctor)) &gt; 0"));
        assertTrue(sql.contains("LENGTH(TRIM(ucl.cd_doctor)) &gt; 0"));
        assertTrue(sql.contains("ucl.cd_doctor AS doctorWorkNo"));
        assertTrue(!sql.contains("ucl.id_doctor AS doctorWorkNo"));
        assertTrue(sql.contains("ucl.consultation_time AS consultationTime"));
        assertTrue(sql.contains("<foreach collection='deviceIds'"));
        assertTrue(sql.contains("<otherwise>AND 1 = 0</otherwise>"));
        assertTrue(sql.contains(") latest WHERE rowNumber = 1"));
    }

    @Test
    void mapperShouldStillExtendUserLogBaseMapper() {
        ParameterizedType generic = (ParameterizedType) AiUserConsultationLogMapper.class.getGenericInterfaces()[0];

        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(AiUserConsultationLog.class, generic.getActualTypeArguments()[0]);
    }
}
