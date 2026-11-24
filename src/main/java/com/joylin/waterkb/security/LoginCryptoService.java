package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * 登录密码传输加密：启动时生成一次 RSA 密钥对，公钥下发给页面，私钥只在内存。
 *
 * <p>前端用 Web Crypto 的 RSA-OAEP(SHA-256) 加密密码，后端在这里解密后再走原有的
 * PBKDF2 校验。密钥不落盘、不进 Git；服务重启后页面会重新拉公钥，不影响使用。
 * 明文入参仍保留兼容（内部验证脚本/运维排障用），但页面一律走加密通道。</p>
 */
@Service
public class LoginCryptoService {

    private KeyPair keyPair;
    private String publicKeyBase64;

    @PostConstruct
    public void init() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    /** DER(S SubjectPublicKeyInfo) 的 Base64，前端 importKey('spki') 直接用 */
    public String publicKey() { return publicKeyBase64; }

    /** 前端加密后的 Base64 密文 → 明文；空值直接返回空串 */
    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) return "";
        try {
            byte[] raw = Base64.getDecoder().decode(cipherBase64);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            OAEPParameterSpec spec = new OAEPParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), spec);
            return new String(cipher.doFinal(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密文被改/格式不对时返回空串，让上层按"密码错误"统一处理，不泄露细节
            return "";
        }
    }
}
