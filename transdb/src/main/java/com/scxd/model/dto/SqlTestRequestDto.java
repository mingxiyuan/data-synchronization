package com.scxd.model.dto;

import lombok.Data;

@Data
public class SqlTestRequestDto {
    private String sourceSql;
    private String markerField;
    private String lastMarkerValue;
    private String sourceWhere;
    private Integer limit;  // 默认1行, 可调
}
