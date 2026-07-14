package com.trans.controller;

import com.trans.config.Response;
import com.trans.service.PasswordEncryptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RSA公钥接口
 * 供前端获取公钥进行密码加密
 */
@RestController
@RequestMapping("/api/v1")
public class EncryptKeyController {

    @Autowired
    private PasswordEncryptService passwordEncryptService;

    /**
     * 获取RSA公钥(JWK格式)
     * GET /api/v1/encrypt-key
     */
    @GetMapping("/encrypt-key")
    public Response getEncryptKey() {
        return passwordEncryptService.getPublicKey();
    }
}
