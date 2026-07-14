package com.trans.service;

import com.trans.config.Response;
import com.trans.model.dto.SyncConfigDeployDto;
import com.trans.model.entity.SyncTask;

public interface SyncTaskService {

    Response list();

    Response add(SyncTask task);

    Response update(String id, SyncTask task);

    Response delete(String id);

    Response detail(String id);

    /** 启动任务 */
    Response start(String id);

    /** 暂停任务 */
    Response pause(String id);

    /** 完全停止任务(不同于暂停) */
    Response stop(String id);

    /** 保存/部署同步配置 */
    Response deploy(SyncConfigDeployDto dto);

    /** 获取当前同步配置 */
    Response getConfig();

    /** 修改任务的last_marker_value(用于数据补录) */
    Response updateMarker(String id, String markerValue);
}
