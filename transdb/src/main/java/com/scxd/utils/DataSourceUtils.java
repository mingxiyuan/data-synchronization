package com.scxd.utils;

import com.alibaba.druid.pool.DruidDataSource;
import com.scxd.dialect.DialectFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态数据源管理工具
 * 根据连接配置动态创建/复用/销毁连接池
 */
@Slf4j
@Component
public class DataSourceUtils {

    /** 连接池缓存: connId -> DruidDataSource */
    private final Map<String, DruidDataSource> dataSourceCache = new ConcurrentHashMap<>();

    /** 最后访问时间: connId -> timestamp, 用于空闲清理 */
    private final Map<String, Long> lastAccessTime = new ConcurrentHashMap<>();

    /** 全局活跃连接池计数 */
    private final AtomicInteger totalPoolCount = new AtomicInteger(0);

    /** 空闲超时: 10分钟 */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    @Value("${transdb.pool.max-active:20}")
    private int poolMaxActive;

    @Value("${transdb.pool.max-total-connections:500}")
    private int maxTotalPools;

    @Value("${transdb.datasource.health-check-enabled:false}")
    private boolean healthCheckEnabled;

    /**
     * 根据连接参数获取数据源(懒加载, 缓存复用)
     */
    public DataSource getDataSource(String connId, String dbType, String jdbcUrl, String username, String password, String driverClass) {
        lastAccessTime.put(connId, System.currentTimeMillis());
        return dataSourceCache.computeIfAbsent(connId, key -> {
            DruidDataSource ds = createDataSource(dbType, jdbcUrl, username, password, driverClass);
            totalPoolCount.incrementAndGet();
            log.info("创建连接池[{}]: dbType={}, maxActive={}, 当前池总数={}", connId, dbType, poolMaxActive, totalPoolCount.get());
            return ds;
        });
    }

    /**
     * 根据连接参数获取Connection
     * 认证错误(密码错误/账号锁定)时自动销毁连接池, 避免后台线程无限重试导致账号被锁
     */
    public Connection getConnection(String connId, String dbType, String jdbcUrl, String username, String password, String driverClass) throws SQLException {
        DataSource ds = getDataSource(connId, dbType, jdbcUrl, username, password, driverClass);
        try {
            Connection conn = ds.getConnection();
            if (healthCheckEnabled) {
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                } catch (SQLException e) {
                    log.warn("连接健康检查失败, 销毁池: connId={}", connId);
                    try { conn.close(); } catch (Exception ignored) {}
                    removeDataSource(connId);
                    throw e;
                }
            }
            return conn;
        } catch (SQLException e) {
            if (isAuthenticationError(e)) {
                log.warn("认证失败, 立即销毁连接池停止后台重试: connId={}, url={}", connId, jdbcUrl);
                removeDataSource(connId);
            }
            throw e;
        }
    }

    /**
     * 直接使用DriverManager测试连接(不创建连接池, 不会触发后台重试, 避免锁定Oracle账号)
     */
    public Connection testConnectionDirect(String dbType, String jdbcUrl, String username, String password, String driverClass) throws SQLException {
        if (driverClass == null || driverClass.trim().isEmpty()) {
            driverClass = inferDriverClass(dbType);
        }
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("数据库驱动未安装: " + driverClass + "，请联系管理员添加对应数据库驱动依赖");
        }
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        // 设置连接超时, 防止长时间阻塞
        if (dbType != null) {
            switch (dbType.toLowerCase()) {
                case "oracle":
                    props.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
                    break;
                case "mysql":
                    props.setProperty("connectTimeout", "10000");
                    props.setProperty("socketTimeout", "30000");
                    break;
                case "dm":
                    props.setProperty("connectTimeout", "10000");
                    break;
                case "postgresql":
                    props.setProperty("connectTimeout", "10");
                    props.setProperty("socketTimeout", "30");
                    break;
                default:
                    break;
            }
        }
        DriverManager.setLoginTimeout(10);
        return DriverManager.getConnection(jdbcUrl, props);
    }

    /**
     * 移除并关闭指定数据源
     */
    public void removeDataSource(String connId) {
        DruidDataSource ds = dataSourceCache.remove(connId);
        lastAccessTime.remove(connId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
            totalPoolCount.decrementAndGet();
            log.info("关闭连接池[{}], 当前池总数={}", connId, totalPoolCount.get());
        }
    }

    /**
     * 清理空闲超时的数据源
     */
    public void cleanIdleDataSources() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastAccessTime.entrySet()) {
            if (now - entry.getValue() > IDLE_TIMEOUT_MS) {
                removeDataSource(entry.getKey());
            }
        }
    }

    /**
     * 根据dbType推断驱动类
     */
    public static String inferDriverClass(String dbType) {
        switch (dbType.toLowerCase()) {
            case "oracle":
                return "oracle.jdbc.OracleDriver";
            case "mysql":
                return "com.mysql.cj.jdbc.Driver";
            case "dm":
                return "dm.jdbc.driver.DmDriver";
            case "postgresql":
                return "org.postgresql.Driver";
            default:
                throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
    }

    /**
     * 检查驱动类是否可用
     */
    public static boolean isDriverAvailable(String driverClass) {
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 判断是否为认证错误(密码错误/账号锁定等), 此类错误重试无意义且可能导致账号被锁
     */
    private boolean isAuthenticationError(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        // Oracle: ORA-01017(无效凭证), ORA-28000(账号锁定), ORA-28001(密码过期)
        // MySQL: Access denied
        // DM: 用户名或密码错误
        return msg.contains("ORA-01017") || msg.contains("ORA-28000") || msg.contains("ORA-28001")
                || msg.contains("Access denied") || msg.contains("authentication")
                || msg.contains("密码错误") || msg.contains("用户名或密码");
    }

    private DruidDataSource createDataSource(String dbType, String jdbcUrl, String username, String password, String driverClass) {
        if (driverClass == null || driverClass.trim().isEmpty()) {
            driverClass = inferDriverClass(dbType);
        }
        // 若dbType为空, 尝试从url推断
        if (dbType == null || dbType.trim().isEmpty()) {
            dbType = DialectFactory.inferDbType(jdbcUrl);
        }
        // 检查驱动类是否可用
        if (!isDriverAvailable(driverClass)) {
            throw new RuntimeException("数据库驱动未安装: " + driverClass + "，请联系管理员添加对应数据库驱动依赖");
        }

        // 全局连接池数上限保护
        if (totalPoolCount.get() >= maxTotalPools) {
            throw new RuntimeException("连接池总数已达上限(" + maxTotalPools + "), 无法创建新的数据库连接池");
        }

        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClass);
        ds.setInitialSize(0);
        ds.setMinIdle(0);
        ds.setMaxActive(poolMaxActive);
        ds.setMaxWait(30000);
        // 连接出错重试间隔60秒, 避免频繁重试锁定Oracle账号(默认极短间隔)
        ds.setTimeBetweenConnectErrorMillis(60000);
        // Druid连接池回收配置
        ds.setTimeBetweenEvictionRunsMillis(120000);
        ds.setMinEvictableIdleTimeMillis(600000);
        ds.setValidationQuery(getValidationQuery(dbType));
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        // 关闭keepAlive, 避免后台线程为维持空闲连接而触发重试
        ds.setKeepAlive(false);

        try {
            ds.init();
            log.info("创建数据源连接池: dbType={}, url={}", dbType, jdbcUrl);
        } catch (SQLException e) {
            ds.close();
            log.error("创建数据源失败: dbType={}, url={}", dbType, jdbcUrl, e);
            throw new RuntimeException("创建数据源失败: " + e.getMessage(), e);
        }
        return ds;
    }

    private String getValidationQuery(String dbType) {
        switch (dbType.toLowerCase()) {
            case "oracle":
                return "SELECT 1 FROM DUAL";
            case "mysql":
                return "SELECT 1";
            case "dm":
                return "SELECT 1 FROM DUAL";
            default:
                return "SELECT 1";
        }
    }
}
