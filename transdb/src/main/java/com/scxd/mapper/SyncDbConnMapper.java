package com.scxd.mapper;

import com.scxd.model.entity.SyncDbConn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface SyncDbConnMapper {

    List<SyncDbConn> listAll();

    SyncDbConn getById(@Param("id") String id);

    int insert(SyncDbConn conn);

    int update(SyncDbConn conn);

    int deleteLogic(@Param("id") String id, @Param("updateTime") Date updateTime);

    int delete(@Param("id") String id);
}
