package com.scxd.controller;

import com.scxd.config.Response;
import com.scxd.model.entity.Schedule;
import com.scxd.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 调度管理
 * 基础路径: /api/v1/schedules
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    /**
     * 获取调度列表
     * GET /api/v1/schedules
     */
    @GetMapping
    public Response list() {
        return scheduleService.list();
    }

    /**
     * 获取单个调度详情
     * GET /api/v1/schedules/{id}
     */
    @GetMapping("/{id}")
    public Response detail(@PathVariable String id) {
        return scheduleService.detail(id);
    }

    /**
     * 创建调度
     * POST /api/v1/schedules
     */
    @PostMapping
    public Response add(@RequestBody Schedule schedule) {
        return scheduleService.add(schedule);
    }

    /**
     * 编辑调度
     * PUT /api/v1/schedules/{id}
     */
    @PutMapping("/{id}")
    public Response update(@PathVariable String id, @RequestBody Schedule schedule) {
        return scheduleService.update(id, schedule);
    }

    /**
     * 删除调度
     * DELETE /api/v1/schedules/{id}
     */
    @DeleteMapping("/{id}")
    public Response delete(@PathVariable String id) {
        return scheduleService.delete(id);
    }

    /**
     * 立即执行一次调度(触发关联的所有同步任务)
     * POST /api/v1/schedules/{id}/trigger
     */
    @PostMapping("/{id}/trigger")
    public Response trigger(@PathVariable String id) {
        return scheduleService.trigger(id);
    }
}
