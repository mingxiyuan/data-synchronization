package com.trans.model.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class SyncMergeDto {
    /** 目标库连接ID */
    private String connId;
    /** 目标表名 */
    private String tableName;
    /** 同步模式: 1=MERGE, 2=INSERT_ONLY, 3=CLEAN_INSERT */
    private Integer syncMode;
    /** 用户自定义主键(逗号分隔), 空则读取原表主键 */
    private String customPk;
    /** 数据列表 */
    private List<Map<String, Object>> data;
}
