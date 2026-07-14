package com.trans.service;

import com.trans.config.Response;

/**
 * 看板/实时监控服务
 */
public interface DashboardService {

    /** 看板概览数据 */
    Response overview();

    /** 性能曲线数据(最近N分钟) */
    Response perfMetrics(Integer minutes, Integer intervalSec);

    /** 同步对象分布数据 */
    Response syncDistribution();

    /** 运行时实时指标(线程池/连接池/活跃任务) */
    Response runtimeMetrics();
}
