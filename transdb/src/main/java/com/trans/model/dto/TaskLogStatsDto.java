package com.trans.model.dto;

import lombok.Data;
import java.util.Date;

/**
 * 任务日志统计DTO（按任务ID分组统计）
 * 替代Map返回，避免oracle.sql.TIMESTAMP序列化问题
 */
@Data
public class TaskLogStatsDto {
    private String taskId;
    private Integer syncCount;
    private Integer successCount;
    private Integer failCount;
    private Date lastSyncTime;
}
