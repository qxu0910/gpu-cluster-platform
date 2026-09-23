package io.clusterplatform.persistence;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Secrets {
    private final SecretKeySpec key;
    public Secrets(Environment env) {
        byte[] bytes=Base64.getDecoder().decode(env.getRequiredProperty("platform.encryption-key"));
        if(bytes.length!=32) throw new IllegalStateException("ENCRYPTION_KEY must be base64 encoded 32 bytes");
        key=new SecretKeySpec(bytes,"AES");
    }
    public String encrypt(String plain) {
        try {
            byte[] nonce=new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception x) { throw new IllegalStateException("secret_encryption_failed"); }
    }
    public String decrypt(String encrypted) {
        try {
            String[] parts=encrypted.split(":"); Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        } catch(Exception x) { throw new IllegalStateException("secret_decryption_failed"); }
    }
}
