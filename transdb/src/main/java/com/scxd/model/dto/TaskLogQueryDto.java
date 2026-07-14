package com.scxd.model.dto;

import lombok.Data;

/**
 * 任务日志分页查询请求
 */
@Data
public class TaskLogQueryDto {
    /** 页码, 默认1 */
    private Integer page;
    /** 每页条数, 默认20, 最大100 */
    private Integer pageSize;

    private String taskId;
    /** 状态过滤: success / fail / warning / running / all */
    private String status;
    /** 搜索关键字(匹配taskId/表名) */
    private String keyword;
}
