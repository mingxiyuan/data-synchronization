package com.trans.model.entity;

import lombok.Data;
import java.util.Date;

@Data
public class Schedule {
    private String id;
    /** 调度名称 */
    private String name;
    /** Cron表达式 */
    private String cron;

    /** 是否一次性调度: true=仅执行一次(手动触发), false=周期调度(按Cron执行) */
    private Boolean once;
    /** 描述 */
    private String desc;
    /** 是否启用 */
    private Boolean enabled;
    /** 1=有效, 0=无效(逻辑删除) */
    private Integer status;
    /** 关联的任务数量(不存库, 查询时统计) */
    private Integer taskCount;
    private Date createTime;
    private Date updateTime;
}
