package com.scxd;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.scxd.mapper")
public class TransdbApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransdbApplication.class, args);
    }
}
