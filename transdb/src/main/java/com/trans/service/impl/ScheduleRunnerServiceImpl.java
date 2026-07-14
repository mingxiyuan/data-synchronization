package com.trans.service.impl;

import com.trans.mapper.ScheduleMapper;
import com.trans.mapper.SyncTaskMapper;
import com.trans.model.entity.Schedule;
import com.trans.model.entity.SyncTask;
import com.trans.service.SyncExecService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时调度执行器
 * 每分钟检查SCHEDULE表, 根据Cron表达式触发关联的同步任务
 */
@Slf4j
@Service
public class ScheduleRunnerServiceImpl {

    @Autowired
    private ScheduleMapper scheduleMapper;

    @Autowired
    private SyncTaskMapper taskMapper;

    @Autowired
    private SyncExecService execService;

    /** 记录每个调度上次触发的时间, 避免同一分钟内重复触发 */
    private final ConcurrentHashMap<String, Long> lastTriggerMinute = new ConcurrentHashMap<>();

    /**
     * 每分钟检查一次: 查找所有启用的周期调度, 判断当前时间是否匹配Cron表达式
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void checkAndTrigger() {
        List<Schedule> schedules;
        try {
            schedules = scheduleMapper.listAll();
        } catch (Exception e) {
            log.error("查询调度列表失败", e);
            return;
        }

        Date now = new Date();
        // 当前分钟(精度到分钟, 避免秒级重复)
        long currentMinute = now.getTime() / (60 * 1000);

        for (Schedule schedule : schedules) {
            try {
                // 跳过未启用、一次性调度、无Cron的调度
                if (!Boolean.TRUE.equals(schedule.getEnabled())) continue;
                if (Boolean.TRUE.equals(schedule.getOnce())) continue;
                if (schedule.getCron() == null || schedule.getCron().trim().isEmpty()) continue;

                // 检查是否在同一分钟内已触发过
                Long lastMinute = lastTriggerMinute.get(schedule.getId());
                if (lastMinute != null && lastMinute == currentMinute) continue;

                // 判断当前时间是否匹配Cron
                if (!isCronMatch(schedule.getCron(), now)) continue;

                // 匹配则触发该调度下的所有任务
                triggerSchedule(schedule, now);
                lastTriggerMinute.put(schedule.getId(), currentMinute);
            } catch (Exception e) {
                log.error("调度[{}]检查异常", schedule.getId(), e);
            }
        }
    }

    /**
     * 判断当前时间是否匹配Cron表达式
     */
    @SuppressWarnings("deprecation")
    private boolean isCronMatch(String cron, Date date) {
        try {
            CronSequenceGenerator gen = new CronSequenceGenerator(cron);
            Date nextExec = gen.next(date);
            if (nextExec == null) return false;
            long diff = nextExec.getTime() - date.getTime();
            return diff >= 0 && diff < 60 * 1000;
        } catch (Exception e) {
            log.warn("Cron表达式[{}]解析失败: {}", cron, e.getMessage());
            return false;
        }
    }

    /**
     * 触发调度关联的所有有效任务
     */
    private void triggerSchedule(Schedule schedule, Date triggerTime) {
        List<SyncTask> tasks = taskMapper.listByScheduleId(schedule.getId());
        if (tasks.isEmpty()) return;

        int triggered = 0;
        for (SyncTask task : tasks) {
            // 跳过暂停状态的任务
            if ("paused".equals(task.getRunStatus())) {
                log.info("调度[{}]关联任务[{}]处于暂停状态, 跳过", schedule.getName(), task.getName());
                continue;
            }
            if ("stopped".equals(task.getRunStatus())) {
                log.info("调度[{}]关联任务[{}]处于停止状态, 跳过", schedule.getName(), task.getName());
                continue;
            }
            try {
                execService.execTask(task.getId());
                triggered++;
                log.info("调度[{}]触发任务[{}]执行", schedule.getName(), task.getName());
            } catch (Exception e) {
                log.error("调度[{}]触发任务[{}]异常", schedule.getName(), task.getName(), e);
            }
        }
        if (triggered > 0) {
            log.info("调度[{}]本次触发{}个任务", schedule.getName(), triggered);
        }
    }
}
