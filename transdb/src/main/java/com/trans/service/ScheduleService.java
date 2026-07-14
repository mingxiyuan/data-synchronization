package com.trans.service;

import com.trans.config.Response;
import com.trans.model.entity.Schedule;

public interface ScheduleService {

    Response list();

    Response detail(String id);

    Response add(Schedule schedule);

    Response update(String id, Schedule schedule);

    Response delete(String id);

    /** 立即执行一次调度(触发关联的所有同步任务) */
    Response trigger(String id);
}
