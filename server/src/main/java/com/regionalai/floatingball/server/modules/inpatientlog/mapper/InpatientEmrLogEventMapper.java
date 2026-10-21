package com.regionalai.floatingball.server.modules.inpatientlog.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.InpatientEmrLogEvent;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface InpatientEmrLogEventMapper extends BaseMapper<InpatientEmrLogEvent> {}
