package com.joylin.waterkb.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** 密码传输加密：前端公钥加密 → 后端私钥解密，密文损坏不泄露细节。 */
class LoginCryptoServiceTest {

    private LoginCryptoService crypto;

    @BeforeEach
    void setUp() throws Exception {
        crypto = new LoginCryptoService();
        crypto.init();
    }

    @Test
    void 公钥加密后能正确解密() throws Exception {
        byte[] der = Base64.getDecoder().decode(crypto.publicKey());
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pub, new OAEPParameterSpec("SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        String cipherText = Base64.getEncoder().encodeToString(
                cipher.doFinal("Admin@123456".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Admin@123456", crypto.decrypt(cipherText));
    }

    @Test
    void 损坏密文返回空串而不是抛异常() {
        assertEquals("", crypto.decrypt("not-base64-cipher"));
        assertEquals("", crypto.decrypt(null));
    }
}
