package com.scxd.model.dto;

import lombok.Data;

@Data
public class SyncProgress {
    private String taskId;
    private String taskName;
    private String status;       // RUNNING / COMPLETED / FAILED
    private long totalRows;
    private long successRows;
    private long failRows;
    private long elapsedMs;
    private long estimatedRemainingMs;
}
