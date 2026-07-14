package com.scxd.model.dto;

import lombok.Data;
import java.util.List;

@Data
public class QueryOutRequestDto {
    /** 源库连接ID */
    private String connId;
    /** 源表名 */
    private String tableName;
    /** 自定义源SQL(非表名时使用, 优先级高于tableName) */
    private String sourceSql;
    /** 查询字段列表, 空则查全部 */
    private List<String> columns;
    /** 查询条件, 如 "STATUS = 1" */
    private String where;
    /** 页码, 默认1 */
    private Integer pageNum;
    /** 每页大小, 默认1000 */
    private Integer pageSize;
}
