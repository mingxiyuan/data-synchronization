package com.scxd.mapper;

import com.scxd.model.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface ScheduleMapper {

    List<Schedule> listAll();

    Schedule getById(@Param("id") String id);

    int insert(Schedule schedule);

    int update(Schedule schedule);

    int deleteLogic(@Param("id") String id, @Param("updateTime") Date updateTime);

    int delete(@Param("id") String id);
}
