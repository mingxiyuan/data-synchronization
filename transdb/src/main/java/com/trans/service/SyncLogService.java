package com.trans.service;

import com.trans.config.Response;
import com.trans.model.dto.TaskLogQueryDto;

public interface SyncLogService {

    /** 分页查询日志列表 */
    Response list(TaskLogQueryDto query);

    /** 日志详情 */
    Response detail(String id);

    /** 今日汇总统计 */
    Response todaySummary();
}
