package com.trans.model.entity;

import lombok.Data;
import java.util.Date;

@Data
public class SyncTaskLog {
    private String id;
    private String taskId;
    private String taskName;
    private Integer syncMode;
    /** 1=运行中, 2=成功, 3=失败 */
    private Integer status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    /** 插入行数 */
    private Integer insertCount;
    /** 更新行数 */
    private Integer updateCount;
    /** 跳过行数 */
    private Integer skipCount;
    private String errorMsg;
    private Date startTime;
    private Date endTime;
}
