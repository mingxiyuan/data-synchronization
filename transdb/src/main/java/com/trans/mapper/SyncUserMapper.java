package com.trans.mapper;

import com.trans.model.entity.SyncUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SyncUserMapper {

    SyncUser findByUsername(@Param("username") String username);

    SyncUser getById(@Param("id") String id);
}
