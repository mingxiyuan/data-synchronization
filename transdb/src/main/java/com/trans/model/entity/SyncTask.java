package com.trans.model.entity;

import lombok.Data;
import java.util.Date;

@Data
public class SyncTask {
    private String id;
    private String name;
    private String sourceConnId;
    private String targetConnId;
    private String sourceTable;
    /** 自定义源SQL(非表名时使用, 优先级高于sourceTable) */
    private String sourceSql;
    private String targetTable;
    /** 字段映射JSON, 如[{"srcField":"LSH","tgtField":"LSH"}] 或 {"SRC_COL":"TGT_COL"}, 空则同名映射 */
    private String fieldMapping;
    /** 用户自定义主键(逗号分隔), 空则读取原表主键 */
    private String customPk;
    /** 增量标记字段名(如UPDATE_TIME/GXSJ), 空则全量同步。对应API_DOC的increment_field */
    private String markerField;
    /** 上次同步的标记值 */
    private String lastMarkerValue;
    /** 断点续传数据(JSON: completed+incomplete), 不影响lastMarkerValue */
    private String checkpointData;
    /** 主键字段列表(逗号分隔), 从field_mappings中isPk=true提取, 冗余便于查询 */
    private String primaryKeys;
    /** 额外查询条件(WHERE条件) */
    private String sourceWhere;
    /** 同步策略: incremental / full / cdc */
    private String strategy;
    /** 数据读取模式: parallel (并行分页, 默认) / streaming (流式单次扫描) */
    private String fetchMode;
    /** 同步模式: 1=MERGE, 2=INSERT_ONLY, 3=CLEAN_INSERT */
    private Integer syncMode;
    /** 批量大小 */
    private Integer batchSize;
    /** 任务分组 */
    private String taskGroup;
    /** 关联的调度ID */
    private String scheduleId;
    /** 任务运行状态: running/paused/stopped/error */
    private String runStatus;
    /** 最后执行时间 */
    private Date lastRunTime;
    /** 下次执行时间 */
    private Date nextRunTime;
    /** 已同步总行数 */
    private Long totalRowsSynced;
    /** 仅插入不更新: true时MERGE模式只INSERT不UPDATE已存在记录 */
    private Boolean noUpdate;
    /** 1=有效, 0=无效(逻辑删除) */
    private Integer status;
    private String remark;
    private Date createTime;
    private Date updateTime;
}
