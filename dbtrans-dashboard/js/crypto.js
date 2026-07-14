/**
 * DBTrans v3.4.0 - Crypto Helper
 * 使用 Web Crypto API (浏览器内置) 进行 RSA-OAEP 加密
 * 公钥从后端动态获取，不硬编码在代码中
 */

const CryptoHelper = {
    publicKey: null,
    keyId: null,      // 保存当前公钥的 kid
    keyExpiry: 0,

    /**
     * 获取 RSA 公钥（带缓存，5分钟有效）
     * @returns {Promise<{publicKey: CryptoKey, keyId: string}|null>}
     */
    async getPublicKey() {
        const now = Date.now();
        if (this.publicKey && this.keyExpiry > now) {
            return { publicKey: this.publicKey, keyId: this.keyId };
        }

        try {
            // 从后端获取公钥（JWK格式）
            const res = await fetch(`${API_BASE_URL}/api/v1/encrypt-key`);
            const data = await res.json();
            
            if (data.code !== 0 || !data.data?.publicKey) {
                throw new Error('获取加密密钥失败');
            }

            const jwk = data.data.publicKey;
            
            // 导入公钥
            this.publicKey = await window.crypto.subtle.importKey(
                'jwk',
                jwk,
                {
                    name: 'RSA-OAEP',
                    hash: 'SHA-256'
                },
                false, // 不可导出
                ['encrypt']
            );

            // 从 JWK 中获取 keyId (kid 字段)
            this.keyId = jwk.kid || data.data.keyId;
            this.keyExpiry = now + 5 * 60 * 1000; // 5分钟缓存
            
            return { publicKey: this.publicKey, keyId: this.keyId };
        } catch (error) {
            console.warn('获取加密密钥失败，将使用明文传输:', error);
            return null;
        }
    },

    /**
     * 加密密码
     * @param {string} password 明文密码
     * @returns {Promise<{encrypted: string, keyId: string}|null>} 加密后的数据或null（失败时）
     */
    async encryptPassword(password) {
        if (!password) return null;

        try {
            const keyData = await this.getPublicKey();
            if (!keyData) return null; // 获取失败，返回null让调用方决定如何处理

            const { publicKey, keyId } = keyData;
            if (!keyId) {
                console.warn('后端未返回 keyId (kid)，无法加密');
                return null;
            }

            // 编码密码
            const encoder = new TextEncoder();
            const data = encoder.encode(password);

            // RSA-OAEP 加密
            const encrypted = await window.crypto.subtle.encrypt(
                {
                    name: 'RSA-OAEP'
                },
                publicKey,
                data
            );

            // 转为 Base64
            const encryptedBase64 = btoa(String.fromCharCode(...new Uint8Array(encrypted)));

            return {
                encrypted: encryptedBase64,
                keyId: keyId  // 使用后端返回的 kid
            };
        } catch (error) {
            console.error('加密失败:', error);
            return null;
        }
    },

    /**
     * 清除缓存的公钥（用于错误重试或登出时）
     */
    clearCache() {
        this.publicKey = null;
        this.keyId = null;
        this.keyExpiry = 0;
    }
};
