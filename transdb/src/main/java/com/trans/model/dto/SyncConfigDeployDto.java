package com.trans.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 同步配置部署请求
 */
@Data
public class SyncConfigDeployDto {
    private String sourceDbId;
    private String targetDbId;
    private List<MappingItem> mappings;

    @Data
    public static class MappingItem {
        private String id;
        private String srcTable;
        private String srcSql;
        private String tgtTable;
        /** incremental / full */
        private String strategy;
        /** WHERE条件 */
        private String filter;
    }
}
