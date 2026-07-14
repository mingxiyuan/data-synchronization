package com.trans.mapper;

import com.trans.model.dto.TaskLogStatsDto;
import com.trans.model.dto.TodaySummaryDto;
import com.trans.model.entity.SyncTaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SyncTaskLogMapper {

    List<SyncTaskLog> listAll();

    /**
     * 带过滤条件的查询（Oracle语法，支持状态、关键字、时间范围、今日过滤等）
     * 参数通过Map传入，支持的key:
     *   status - Integer, 状态过滤
     *   keyword - String, 关键字模糊匹配(taskId/taskName)
     *   startTimeAfter - Date, 开始时间大于等于
     *   endTimeAfter - Date, 结束时间大于等于
     *   endTimeBefore - Date, 结束时间小于等于
     *   startTimeFrom - Date, 开始时间范围起
     *   startTimeTo - Date, 开始时间范围止
     *   today - Boolean, 是否只查今日
     */
    List<SyncTaskLog> listByCondition(Map<String, Object> params);

    /**
     * 带过滤条件的查询（MySQL语法兼容版）
     */
    List<SyncTaskLog> listByConditionMysql(Map<String, Object> params);

    List<SyncTaskLog> listByTaskId(@Param("taskId") String taskId);

    SyncTaskLog getById(@Param("id") String id);

    int insert(SyncTaskLog log);

    int updateResult(SyncTaskLog log);

    SyncTaskLog getLatestByTaskId(@Param("taskId") String taskId);

    /**
     * 今日日志统计（数据库端聚合，Oracle语法）
     */
    TodaySummaryDto todaySummary();

    /**
     * 今日日志统计（数据库端聚合，MySQL语法）
     */
    TodaySummaryDto todaySummaryMysql();

    /**
     * 按任务ID批量统计（避免N+1查询）
     */
    List<TaskLogStatsDto> countByTaskIdGroupByStatus(@Param("taskIds") List<String> taskIds);
}
