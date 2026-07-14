package com.scxd.mapper;

import com.scxd.model.entity.SyncTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface SyncTaskMapper {

    List<SyncTask> listAll();

    /** 按运行状态列表查询(用于启动恢复僵尸任务) */
    List<SyncTask> listByStatuses(@Param("statuses") List<String> statuses);

    SyncTask getById(@Param("id") String id);

    List<SyncTask> listByScheduleId(@Param("scheduleId") String scheduleId);

    /**
     * 按调度ID统计任务数（避免全表查出后在Java中统计）
     */
    int countByScheduleId(@Param("scheduleId") String scheduleId);

    /**
     * 检查调度下是否存在有效任务
     */
    int existsByScheduleId(@Param("scheduleId") String scheduleId);

    int insert(SyncTask task);

    int update(SyncTask task);

    int updateMarker(@Param("id") String id, @Param("lastMarkerValue") String lastMarkerValue, @Param("updateTime") Date updateTime);

    int updateCheckpoint(@Param("id") String id, @Param("checkpointData") String checkpointData);

    /** 直接置NULL, 避免MyBatis+DM CLOB NULL绑定问题 */
    int clearCheckpoint(@Param("id") String id);

    int updateRunStatus(@Param("id") String id, @Param("runStatus") String runStatus, @Param("updateTime") Date updateTime);

    int deleteLogic(@Param("id") String id, @Param("updateTime") Date updateTime);

    int delete(@Param("id") String id);
}
