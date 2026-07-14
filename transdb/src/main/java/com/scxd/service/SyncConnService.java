package com.scxd.service;

import com.scxd.config.Response;
import com.scxd.model.dto.TestConnRequestDto;
import com.scxd.model.entity.SyncDbConn;

public interface SyncConnService {

    Response list();

    Response add(SyncDbConn conn);

    Response update(String id, SyncDbConn conn);

    Response delete(String id);

    /** 测试连接是否可用 */
    Response testConnection(TestConnRequestDto dto);

    /** 根据已保存的连接ID测试连通性 */
    Response testConnectionById(String id);

    /** 获取该连接下的表列表 */
    Response getTableList(String dbId, String role);

    /** 获取指定表的字段列表 */
    Response getColumns(String connId, String tableName);

    /** 获取指定表的主键 */
    Response getPrimaryKeys(String connId, String tableName);

    /** 根据SQL获取列名和数据类型 */
    Response getSqlColumns(String connId, String sql);
}
