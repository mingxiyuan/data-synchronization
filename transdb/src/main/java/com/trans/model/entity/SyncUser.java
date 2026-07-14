package com.trans.model.entity;

import lombok.Data;
import java.util.Date;

@Data
public class SyncUser {
    private String id;
    private String username;
    private String password;
    private String realName;
    private String role;
    private Integer status;
    private Date createTime;
}
