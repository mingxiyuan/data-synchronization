package com.trans.model.dto;

import lombok.Data;

/**
 * 测试连通性结果
 */
@Data
public class TestConnResultDto {
    private Boolean connected;
    private Long latencyMs;
    private String version;
    private String error;
}
