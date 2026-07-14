package com.trans.service;

import com.trans.config.Response;
import com.trans.model.dto.QueryOutRequestDto;
import com.trans.model.dto.SyncMergeDto;

public interface SyncExecService {

    /** 执行同步任务(出库→入库一体) */
    Response execTask(String taskId);

    /** 停止正在运行的同步任务 */
    boolean stopTask(String taskId);

    /** 单独出库 */
    Response queryOut(QueryOutRequestDto request);

    /** 单独入库 */
    Response mergeInto(SyncMergeDto request);

    /** 预览同步数据(不实际执行) */
    Response preview(String taskId);

    /** 获取任务同步进度 */
    Response getProgress(String taskId);

}
