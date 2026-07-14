package com.trans.service.impl;

import com.trans.config.Response;
import com.trans.mapper.ScheduleMapper;
import com.trans.mapper.SyncTaskMapper;
import com.trans.model.entity.Schedule;
import com.trans.model.entity.SyncTask;
import com.trans.service.ScheduleService;
import com.trans.service.SyncExecService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ScheduleServiceImpl implements ScheduleService {

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private SyncTaskMapper taskMapper;

    @Autowired
    private SyncExecService execService;

    @Override
    public Response list() {
        List<Schedule> list = scheduleMapper.listAll();
        // 使用SQL统计每个调度关联的任务数，避免全表查出后Java内存统计
        for (Schedule schedule : list) {
            int taskCount = taskMapper.countByScheduleId(schedule.getId());
            schedule.setTaskCount(taskCount);
        }
        return Response.success(list);
    }

    @Override
    public Response detail(String id) {
        Schedule schedule = scheduleMapper.getById(id);
        if (schedule == null) {
            return Response.configNotFound("调度不存在");
        }
        // 使用SQL统计关联任务数
        int taskCount = taskMapper.countByScheduleId(id);
        schedule.setTaskCount(taskCount);
        return Response.success(schedule);
    }

    @Override
    public Response add(Schedule schedule) {
        // once=true(一次性调度)时cron可为空，否则cron必填
        boolean isOnce = Boolean.TRUE.equals(schedule.getOnce());
        if (!isOnce) {
            if (schedule.getCron() == null || schedule.getCron().trim().isEmpty()) {
                return Response.paramError("Cron表达式不能为空");
            }
            if (!isValidCron(schedule.getCron())) {
                return Response.paramError("Cron表达式格式不正确");
            }
        }
        schedule.setId(UUID.randomUUID().toString().replace("-", ""));
        if (schedule.getOnce() == null) schedule.setOnce(false);
        schedule.setEnabled(schedule.getEnabled() != null ? schedule.getEnabled() : true);
        Date now = new Date();
        schedule.setCreateTime(now);
        schedule.setUpdateTime(now);
        scheduleMapper.insert(schedule);
        return Response.success(schedule);
    }

    @Override
    public Response update(String id, Schedule schedule) {
        Schedule existing = scheduleMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("调度不存在");
        }
        // 合并：前端只传部分字段，未传的保留原值
        if (schedule.getName() == null) schedule.setName(existing.getName());
        if (schedule.getOnce() == null) schedule.setOnce(existing.getOnce());
        boolean isOnce = Boolean.TRUE.equals(schedule.getOnce());
        // once=1时cron可为空，否则校验cron
        if (!isOnce && schedule.getCron() != null && !isValidCron(schedule.getCron())) {
            return Response.paramError("Cron表达式格式不正确");
        }
        if (schedule.getCron() == null) schedule.setCron(existing.getCron());
        if (schedule.getDesc() == null) schedule.setDesc(existing.getDesc());
        if (schedule.getEnabled() == null) schedule.setEnabled(existing.getEnabled());
        schedule.setId(id);
        schedule.setUpdateTime(new Date());
        scheduleMapper.update(schedule);
        return Response.success(scheduleMapper.getById(id));
    }

    @Override
    public Response delete(String id) {
        Schedule existing = scheduleMapper.getById(id);
        if (existing == null) {
            return Response.configNotFound("调度不存在");
        }
        // 使用SQL检查是否有关联的任务，避免全表查询
        int taskCount = taskMapper.countByScheduleId(id);
        if (taskCount > 0) {
            return Response.error("该调度下有关联的同步任务，无法删除");
        }
        scheduleMapper.deleteLogic(id, new Date());
        return Response.success("删除成功");
    }

    @Override
    public Response trigger(String id) {
        Schedule schedule = scheduleMapper.getById(id);
        if (schedule == null) {
            return Response.configNotFound("调度不存在");
        }
        // 查找该调度关联的所有有效任务
        List<SyncTask> tasks = taskMapper.listByScheduleId(id);
        if (tasks.isEmpty()) {
            return Response.error("该调度下没有关联的同步任务");
        }
        List<String> triggeredTasks = new ArrayList<>();
        for (SyncTask task : tasks) {
            // 跳过paused状态的任务
            if ("paused".equals(task.getRunStatus())) {
                log.info("任务[{}]处于暂停状态，跳过执行", task.getId());
                continue;
            }
            try {
                execService.execTask(task.getId());
                triggeredTasks.add(task.getId());
                log.info("调度[{}]触发任务[{}]执行成功", id, task.getId());
            } catch (Exception e) {
                log.error("调度[{}]触发任务[{}]执行异常: {}", id, task.getId(), e.getMessage());
                triggeredTasks.add(task.getId()); // 仍然记录为已触发
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("message", "调度已触发");
        result.put("triggered_tasks", triggeredTasks);
        result.put("trigger_time", new Date());
        return Response.success(result);
    }

    /**
     * 简单校验Cron表达式格式（6-7段）
     */
    private boolean isValidCron(String cron) {
        if (cron == null || cron.trim().isEmpty()) {
            return false;
        }
        String[] parts = cron.trim().split("\\s+");
        return parts.length >= 6 && parts.length <= 7;
    }
}
