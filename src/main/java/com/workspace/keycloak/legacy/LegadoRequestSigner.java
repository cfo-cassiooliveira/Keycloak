package com.workspace.keycloak.legacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Assina as chamadas à API interna do Legado (ADR-0005 do legado), no mesmo formato que o Legado valida:
 * {@code keyId=<id>, t=<unix s>, n=<nonce base64url>, v1=<base64(HMAC-SHA256(chave, canônico))>}, com
 * canônico = {@code t \n n \n MÉTODO \n rota+query \n hex(SHA-256(corpo))}.
 */
public final class LegadoRequestSigner {

    /** Header que leva a assinatura. */
    public static final String HEADER_NAME = "X-Legado-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int NONCE_BYTES = 16;

    private final String keyId;
    private final SecretKeySpec key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LegadoRequestSigner(String keyId, byte[] secret, Clock clock) {
        this.keyId = keyId;
        this.key = new SecretKeySpec(secret, HMAC_ALGORITHM);
        this.clock = clock;
    }

    /** Valor do header para uma chamada, com horário atual e nonce novo (16 bytes aleatórios, base64url). */
    public String sign(String method, String pathAndQuery, byte[] body) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return sign(clock.instant().getEpochSecond(), Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                method, pathAndQuery, body);
    }

    /** Variante determinística (vetores de teste). */
    String sign(long timestamp, String nonce, String method, String pathAndQuery, byte[] body) {
        String canonical = timestamp + "\n" + nonce + "\n" + method.toUpperCase(Locale.ROOT) + "\n" + pathAndQuery
                + "\n" + HexFormat.of().formatHex(sha256(body));
        return "keyId=" + keyId + ", t=" + timestamp + ", n=" + nonce + ", v1="
                + Base64.getEncoder().encodeToString(hmac(canonical));
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "LegadoRequestSigner[keyId=" + keyId + "]";
    }
}
