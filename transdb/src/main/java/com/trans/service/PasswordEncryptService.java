package com.trans.service;

import com.trans.config.Response;

/**
 * 密码加密服务
 * - RSA-OAEP: 前端传输加密
 * - AES-256: 数据库存储加密
 */
public interface PasswordEncryptService {

    /**
     * 获取当前RSA公钥(JWK格式)
     */
    Response getPublicKey();

    /**
     * RSA解密密码
     * @param encryptedPassword Base64编码的RSA-OAEP加密数据
     * @param keyId 密钥标识符(kid)
     * @return 明文密码
     */
    String decryptPassword(String encryptedPassword, String keyId) throws Exception;

    /**
     * 处理密码: 如果encrypted=true则RSA解密, 否则原样返回
     */
    String resolvePassword(String password, Boolean encrypted, String keyId);

    /**
     * AES-256加密密码(用于存入数据库)
     * @param plaintext 明文密码
     * @return AES加密后的Base64字符串(带前缀标识)
     */
    String aesEncrypt(String plaintext);

    /**
     * AES-256解密密码(从数据库读出后解密)
     * @param ciphertext AES加密后的Base64字符串(带前缀标识)
     * @return 明文密码
     */
    String aesDecrypt(String ciphertext);
}
