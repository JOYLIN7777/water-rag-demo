package com.joylin.waterkb.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 登录密码传输加密：RSA-OAEP(SHA-256)，公钥下发页面，私钥只在本进程内存。
 *
 */
@Service
public class LoginCryptoService {

    private static final Logger log = LoggerFactory.getLogger(LoginCryptoService.class);

    @Value("${LOGIN_RSA_PRIVATE_KEY:}")
    private String configuredPrivateKey;

    private KeyPair keyPair;
    private String publicKeyBase64;

    @PostConstruct
    public void init() throws Exception {
        if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
            try {
                byte[] der = Base64.getDecoder().decode(configuredPrivateKey.trim());
                PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
                PublicKey pub = derivePublicKey(priv);
                keyPair = new KeyPair(pub, priv);
                publicKeyBase64 = Base64.getEncoder().encodeToString(pub.getEncoded());
                log.info("登录加密密钥：已加载 LOGIN_RSA_PRIVATE_KEY（固定密钥，重启/多实例一致）");
            } catch (Exception e) {
                log.error("LOGIN_RSA_PRIVATE_KEY 解析失败，启动中止（不悄悄退回随机密钥，避免登录时好时坏）", e);
                throw e;
            }
        } else {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keyPair = gen.generateKeyPair();
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            log.warn("登录加密密钥：未配置 LOGIN_RSA_PRIVATE_KEY，使用启动时随机密钥。" +
                    "本机开发可用；生产/多实例必须配置固定密钥。");
        }
    }

    /**
     * 从 PKCS#8 RSA 私钥推导公钥（CRT 参数含模数与指数）
     */
    private PublicKey derivePublicKey(PrivateKey priv) throws Exception {
        if (priv instanceof RSAPrivateCrtKey crt) {
            RSAPublicKeySpec spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
        throw new IllegalArgumentException("LOGIN_RSA_PRIVATE_KEY 必须是 PKCS#8 格式的 RSA 私钥（含 CRT 参数）");
    }

    /**
     * DER(SubjectPublicKeyInfo) 的 Base64，前端 importKey('spki') 直接用
     */
    public String publicKey() { return publicKeyBase64; }

    /**
     * 前端加密后的 Base64 密文 → 明文；空值返回空串
     */
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
            // 密文被改/公钥过期时返回空串，上层按"密码错误"统一处理，不泄露细节
            return "";
        }
    }
}
