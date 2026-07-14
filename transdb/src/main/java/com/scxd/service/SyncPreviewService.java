package com.scxd.service;

import com.scxd.config.Response;
import com.scxd.model.dto.SqlTestRequestDto;

import java.util.List;

public interface SyncPreviewService {

    Response preview(String taskId);

    Response sqlBuild(String dbId, SqlTestRequestDto req);

    Response sqlTest(String dbId, SqlTestRequestDto req);

    Response sourceMax(String taskId);

    Response batchSourceMax(List<String> taskIds);
}
