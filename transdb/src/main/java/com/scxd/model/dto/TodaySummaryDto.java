package com.scxd.model.dto;

import lombok.Data;

/**
 * 今日日志汇总统计DTO
 * 替代Map返回，避免Oracle类型序列化问题
 */
@Data
public class TodaySummaryDto {
    private Integer totalExecutions;
    private Integer successCount;
    private Integer failCount;
    private Integer warningCount;
    private Integer totalRowsSynced;
    private Integer totalErrorRows;
    private Long totalDurationMs;
}
