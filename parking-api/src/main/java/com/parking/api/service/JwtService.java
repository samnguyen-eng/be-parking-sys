package com.parking.api.service;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.kms.v1.CryptoKeyVersionName;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class JwtService {

    // Cached KMS client and public key — initialized once at startup
    private KeyManagementServiceClient kmsClient;
    private PublicKey cachedPublicKey;

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    @Value("${app.jwt.kms.enabled:false}")
    private boolean kmsEnabled;

    @Value("${app.jwt.kms.key-version:}")
    private String legacyKmsKeyVersion;

    @Value("${app.kms.project-id:}")
    private String kmsProjectId;

    @Value("${app.kms.location-id:asia-southeast1}")
    private String kmsLocationId;

    @Value("${app.kms.key-ring-id:parking-keyring}")
    private String kmsKeyRingId;

    @Value("${app.kms.jwt-key-id:parking-jwt-key}")
    private String kmsJwtKeyId;

    @Value("${app.kms.jwt-key-version:primary}")
    private String kmsJwtKeyVersion;

    @Value("${app.jwt.kms.typ:JWT}")
    private String jwtType;

    @PostConstruct
    public void init() {
        if (isKmsActive()) {
            try {
                kmsClient = KeyManagementServiceClient.create();
                cachedPublicKey = loadPublicKeyFromKms();
                log.info("KMS client initialized and public key cached successfully");
            } catch (Exception ex) {
                log.error("Failed to initialize KMS client: {}", ex.getMessage(), ex);
                throw new RuntimeException("KMS initialization failed", ex);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (kmsClient != null) {
            kmsClient.close();
            log.info("KMS client closed");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(final String username) {
        return generateToken(username, null);
    }

    public String generateToken(final String username, final Long userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        if (isKmsActive()) {
            try {
                return generateTokenWithKms(username, userId, now.toInstant(), expiry.toInstant());
            } catch (Exception ex) {
                throw new RuntimeException("Failed to generate JWT with KMS", ex);
            }
        }

        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry);
        if (userId != null) {
            builder.claim("uid", userId);
        }
        return builder.signWith(getSigningKey()).compact();
    }

    public Long extractUserId(final String token) {
        Claims claims = isKmsActive() ? parseClaimsWithKms(token) : parseClaims(token);
        Object uid = claims.get("uid");
        if (uid == null) {
            return null;
        }
        if (uid instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(uid.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public String extractUsername(final String token) {
        if (isKmsActive()) {
            return parseClaimsWithKms(token).getSubject();
        }
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(final String token) {
        try {
            if (isKmsActive()) {
                parseClaimsWithKms(token);
            } else {
                parseClaims(token);
            }
            return true;
        } catch (ExpiredJwtException ex) {
            log.warn("JWT token expired: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.warn("JWT token unsupported: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.warn("JWT token malformed: {}", ex.getMessage());
        } catch (SecurityException ex) {
            log.warn("JWT signature invalid: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("JWT claims empty: {}", ex.getMessage());
        }
        return false;
    }

    private Claims parseClaimsWithKms(final String token) {
        return Jwts.parser()
                .verifyWith(cachedPublicKey)  // use cached key — no KMS call
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Claims parseClaims(final String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String generateTokenWithKms(final String username, final Long userId,
                                        final Instant issuedAt, final Instant expiresAt)
            throws IOException {
        String header = toBase64UrlJson(Map.of("alg", "RS256", "typ", jwtType));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", username);
        if (userId != null) {
            payload.put("uid", userId);
        }
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        String body = toBase64UrlJson(payload);
        String signingInput = header + "." + body;
        String signature = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(signWithKms(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return signingInput + "." + signature;
    }

    private byte[] signWithKms(byte[] signingInput) throws IOException {
        try {
            byte[] digestBytes = MessageDigest.getInstance("SHA-256").digest(signingInput);
            return kmsClient.asymmetricSign(
                            AsymmetricSignRequest.newBuilder()
                                    .setName(resolveJwtSigningKeyVersionName(kmsClient))
                                    .setDigest(Digest.newBuilder()
                                            .setSha256(com.google.protobuf.ByteString.copyFrom(digestBytes))
                                            .build())
                                    .build())
                    .getSignature()
                    .toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to sign JWT by KMS asymmetric key", ex);
        }
    }

    private PublicKey loadPublicKeyFromKms() {
        try {
            String pem = kmsClient.getPublicKey(GetPublicKeyRequest.newBuilder()
                            .setName(resolveJwtSigningKeyVersionName(kmsClient))
                            .build())
                    .getPem();
            String cleaned = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load KMS public key", ex);
        }
    }

    private boolean isKmsActive() {
        return kmsEnabled
                && (StringUtils.hasText(legacyKmsKeyVersion)
                || (StringUtils.hasText(kmsProjectId)
                && StringUtils.hasText(kmsLocationId)
                && StringUtils.hasText(kmsKeyRingId)
                && StringUtils.hasText(kmsJwtKeyId)));
    }

    private String toBase64UrlJson(Map<String, ?> value) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to encode JWT JSON segment", ex);
        }
    }

    private String resolveJwtSigningKeyVersionName(KeyManagementServiceClient kmsClient) {
        if (StringUtils.hasText(legacyKmsKeyVersion)) {
            return legacyKmsKeyVersion;
        }

        if ("primary".equalsIgnoreCase(kmsJwtKeyVersion)) {
            CryptoKeyName keyName = CryptoKeyName.of(kmsProjectId, kmsLocationId, kmsKeyRingId, kmsJwtKeyId);
            return kmsClient.getCryptoKey(keyName).getPrimary().getName();
        }

        return CryptoKeyVersionName.of(kmsProjectId, kmsLocationId, kmsKeyRingId, kmsJwtKeyId, kmsJwtKeyVersion)
                .toString();
    }
}
