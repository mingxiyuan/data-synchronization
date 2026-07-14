package com.scxd.model.dto;

import lombok.Data;

/**
 * 测试数据库连通性请求
 */
@Data
public class TestConnRequestDto {
    private String type;
    private String host;
    private Integer port;
    private String database;
    private String user;
    private String password;
    /** 完整JDBC URL, 优先使用此字段, 如: jdbc:dm://11.101.2.166:5236?SCHEMA=YSP */
    private String url;
    /** 密码是否已RSA加密 */
    private Boolean encrypted;
    /** RSA密钥标识符(kid) */
    private String keyId;
}
