package com.joylin.waterkb.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希工具：PBKDF2WithHmacSHA256（JDK 自带，零新增依赖）。
 *
 *
 */
@Component
public class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;   // OWASP 2023 建议 SHA256 至少 10 万轮
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    /**

     * 对明文密码生成自描述哈希串（注册/初始化账号时用）

     */
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(rawPassword, salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(derived);
    }

    /**

     * 校验明文密码与存储的哈希是否匹配；任何格式异常都当作不匹配（不抛给调用方）

     */
    public boolean matches(String rawPassword, String stored) {
        try {
            String[] p = stored.split("\\$", 4);
            if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
            int iter = Integer.parseInt(p[1]);
            byte[] salt = B64D.decode(p[2]);
            byte[] expected = B64D.decode(p[3]);
            byte[] actual = derive(rawPassword, salt, iter);
            // 常量时间比较，防时序侧信道
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] derive(String raw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希计算失败", e);
        }
    }
}
