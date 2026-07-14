package com.trans.service.impl;

import com.trans.config.BusinessException;
import com.trans.config.Response;
import com.trans.config.ResultCodeEnum;
import com.trans.service.PasswordEncryptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密码加密服务实现
 * - RSA-OAEP with SHA-256: 前端传输加密
 * - AES-256-GCM: 数据库存储加密
 */
@Slf4j
@Service
public class PasswordEncryptServiceImpl implements PasswordEncryptService {

    // ======================== AES-256 存储加密 ========================

    /** AES加密后密文前缀, 用于判断密码是否已AES加密 */
    private static final String AES_PREFIX = "ENC(";
    private static final String AES_SUFFIX = ")";
    /** AES-256-GCM参数 */
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** AES密钥(32字节=256bit), 从配置读取 */
    private final byte[] aesKey;

    public PasswordEncryptServiceImpl(@Value("${transdb.aes-key:}") String aesKeyHex) {
        // 初始化RSA
        rotateKey();
        // 初始化AES密钥
        if (aesKeyHex != null && !aesKeyHex.trim().isEmpty()) {
            this.aesKey = hexToBytes(aesKeyHex.trim());
            if (this.aesKey.length != 32) {
                throw new IllegalArgumentException("transdb.aes-key 必须为64个十六进制字符(32字节/256位)");
            }
            log.info("AES-256密钥已从配置加载");
        } else {
            // 未配置则自动生成, 每次重启不同(生产环境必须配置)
            this.aesKey = new byte[32];
            new SecureRandom().nextBytes(this.aesKey);
            log.warn("未配置transdb.aes-key, 使用随机AES密钥(重启后旧密文将无法解密, 生产环境务必配置!)");
            log.warn("建议在application.properties中添加: transdb.aes-key={}", bytesToHex(this.aesKey));
        }
    }

    @Override
    public String aesEncrypt(String plaintext) {
        if (plaintext == null || plaintext.trim().isEmpty()) {
            return plaintext;
        }
        try {
            // 生成随机IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 拼接: IV + 密文(含tag), 然后Base64编码, 加前缀标识
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            String base64 = Base64.getEncoder().encodeToString(combined);
            return AES_PREFIX + base64 + AES_SUFFIX;
        } catch (Exception e) {
            log.error("AES加密失败", e);
            throw new RuntimeException("AES加密失败", e);
        }
    }

    @Override
    public String aesDecrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return ciphertext;
        }
        // 判断是否为AES加密格式: ENC(Base64)
        if (!ciphertext.startsWith(AES_PREFIX) || !ciphertext.endsWith(AES_SUFFIX)) {
            // 非AES加密格式, 视为明文(兼容旧数据)
            return ciphertext;
        }
        try {
            String base64 = ciphertext.substring(AES_PREFIX.length(), ciphertext.length() - AES_SUFFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64);

            // 提取IV和密文
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES解密失败, 密文可能由不同AES密钥加密", e);
            throw new RuntimeException("AES解密失败", e);
        }
    }

    // ======================== RSA 传输加密 ========================

    /** 当前活跃密钥对 */
    private volatile KeyPair activeKeyPair;
    /** 当前密钥ID */
    private volatile String activeKeyId;
    /** 密钥创建时间 */
    private volatile long activeKeyCreatedAt;

    /** 旧密钥对缓存(用于解密旧请求), keyId -> KeyPair */
    private final Map<String, KeyPair> oldKeyPairs = new ConcurrentHashMap<>();
    /** 旧密钥过期时间, keyId -> expireTimestamp */
    private final Map<String, Long> oldKeyExpiry = new ConcurrentHashMap<>();

    /** 密钥有效期: 24小时 */
    private static final long KEY_TTL_MS = 24 * 60 * 60 * 1000L;
    /** 旧密钥保留期: 24小时(从新密钥生成时算起) */
    private static final long OLD_KEY_RETAIN_MS = 24 * 60 * 60 * 1000L;

    @Override
    public Response getPublicKey() {
        checkAndRotate();

        try {
            PublicKey publicKey = activeKeyPair.getPublic();
            byte[] encoded = publicKey.getEncoded();

            X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pubKey = kf.generatePublic(spec);

            java.math.BigInteger modulus = ((java.security.interfaces.RSAPublicKey) pubKey).getModulus();
            java.math.BigInteger exponent = ((java.security.interfaces.RSAPublicKey) pubKey).getPublicExponent();

            String n = base64UrlEncode(modulus.toByteArray());
            String e = base64UrlEncode(exponent.toByteArray());

            Map<String, Object> publicKeyJwk = new LinkedHashMap<>();
            publicKeyJwk.put("kty", "RSA");
            publicKeyJwk.put("alg", "RSA-OAEP-256");
            publicKeyJwk.put("use", "enc");
            publicKeyJwk.put("n", n);
            publicKeyJwk.put("e", e);
            publicKeyJwk.put("kid", activeKeyId);

            long expiresAt = activeKeyCreatedAt + KEY_TTL_MS;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("publicKey", publicKeyJwk);
            data.put("expiresAt", new Date(expiresAt));

            return Response.success(data);
        } catch (Exception ex) {
            log.error("获取公钥失败", ex);
            return Response.error("获取公钥失败: " + ex.getMessage());
        }
    }

    @Override
    public String decryptPassword(String encryptedPassword, String keyId) throws Exception {
        if (encryptedPassword == null || encryptedPassword.trim().isEmpty()) {
            return encryptedPassword;
        }

        KeyPair keyPair = null;
        if (keyId != null && keyId.equals(activeKeyId)) {
            keyPair = activeKeyPair;
        } else if (keyId != null) {
            keyPair = oldKeyPairs.get(keyId);
        }
        if (keyPair == null) {
            keyPair = activeKeyPair;
        }

        PrivateKey privateKey = keyPair.getPrivate();
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedPassword);

        // RSA-OAEP解密: MGF1=SHA-256(与Web Crypto API一致)
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepParams);
        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);

        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    @Override
    public String resolvePassword(String password, Boolean encrypted, String keyId) {
        if (password == null || password.trim().isEmpty()) {
            return password;
        }
        if (!Boolean.TRUE.equals(encrypted)) {
            return password;
        }
        try {
            return decryptPassword(password, keyId);
        } catch (Exception e) {
            log.error("RSA密码解密失败", e);
            throw new BusinessException(ResultCodeEnum.PARAM_ERROR, "密码解密失败，请重新获取公钥加密");
        }
    }

    // ======================== RSA密钥管理 ========================

    private void checkAndRotate() {
        if (System.currentTimeMillis() - activeKeyCreatedAt > KEY_TTL_MS) {
            rotateKey();
        }
        cleanExpiredOldKeys();
    }

    private synchronized void rotateKey() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            KeyPair newKeyPair = kpg.generateKeyPair();

            String newKeyId = "rsa-key-" + String.format("%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS", new Date());

            if (activeKeyPair != null && activeKeyId != null) {
                oldKeyPairs.put(activeKeyId, activeKeyPair);
                oldKeyExpiry.put(activeKeyId, System.currentTimeMillis() + OLD_KEY_RETAIN_MS);
            }

            this.activeKeyPair = newKeyPair;
            this.activeKeyId = newKeyId;
            this.activeKeyCreatedAt = System.currentTimeMillis();

            log.info("RSA密钥轮换: newKeyId={}", newKeyId);
        } catch (Exception e) {
            log.error("RSA密钥轮换失败", e);
            throw new RuntimeException("RSA密钥轮换失败", e);
        }
    }

    private void cleanExpiredOldKeys() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : oldKeyExpiry.entrySet()) {
            if (now > entry.getValue()) {
                oldKeyPairs.remove(entry.getKey());
                oldKeyExpiry.remove(entry.getKey());
                log.info("清理过期RSA密钥: keyId={}", entry.getKey());
            }
        }
    }

    private String base64UrlEncode(byte[] data) {
        int start = 0;
        while (start < data.length - 1 && data[start] == 0) {
            start++;
        }
        byte[] trimmed = Arrays.copyOfRange(data, start, data.length);
        String base64 = Base64.getEncoder().encodeToString(trimmed);
        return base64.replace('+', '-').replace('/', '_').replace("=", "");
    }

    // ======================== 工具方法 ========================

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
