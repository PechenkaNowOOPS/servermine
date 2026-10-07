package ru.servermine.economy.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class CurrencySigner {
    private static final String HMAC = "HmacSHA256";
    private final byte[] secret;

    private CurrencySigner(byte[] secret) {
        this.secret = secret.clone();
    }

    static CurrencySigner loadOrCreate(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            String encoded = Files.readString(path).trim();
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length < 32) throw new IOException("Currency secret is too short");
            return new CurrencySigner(bytes);
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        Files.writeString(path, Base64.getEncoder().encodeToString(bytes));
        return new CurrencySigner(bytes);
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign currency", e);
        }
    }

    boolean verify(String payload, String signature) {
        if (signature == null) return false;
        byte[] expected = sign(payload).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
