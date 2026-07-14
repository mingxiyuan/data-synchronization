package com.trans.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 主数据源配置
 * @ConfigurationProperties 从 spring.datasource.* 绑定 url/username/password/driver
 * 方法体内仅设置 Druid 连接池参数
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        DruidDataSource ds = new DruidDataSource();
        ds.setTimeBetweenConnectErrorMillis(60000);
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        ds.setTimeBetweenEvictionRunsMillis(120000);
        ds.setMinEvictableIdleTimeMillis(300000);
        ds.setInitialSize(0);
        ds.setMinIdle(0);
        ds.setMaxActive(5);
        ds.setMaxWait(5000);
        return ds;
    }
}
