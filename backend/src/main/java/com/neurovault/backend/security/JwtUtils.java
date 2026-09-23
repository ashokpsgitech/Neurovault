package com.neurovault.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * Utility class for generating, parsing, and validating JSON Web Tokens (JWT).
 */
@Component
@Slf4j
public class JwtUtils {

    private final SecretKey key;
    private final long expirationMs;

    private static final String INSECURE_DEFAULT_SECRET = "4035763074377938355668596D6235723D723930516D61365468576D5A713474";

    public JwtUtils(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            org.springframework.core.env.Environment env) {
        this.expirationMs = expirationMs;

        boolean isProd = false;
        for (String profile : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                isProd = true;
                break;
            }
        }

        if (isProd && INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException("CRITICAL SECURITY ERROR: Insecure default JWT secret detected in production profile! Set APP_JWT_SECRET environment variable.");
        } else if (INSECURE_DEFAULT_SECRET.equals(secret)) {
            log.warn("SECURITY WARNING: Using default development JWT secret. Override APP_JWT_SECRET before deploying to production.");
        }

        // Load key either as raw bytes or base64 depending on length and complexity.
        // We'll decode using base64 if it's base64-encoded, or fall back to UTF-8 bytes.
        SecretKey localKey;
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret);
            localKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            log.warn("Failed to decode secret as base64, using raw UTF-8 bytes instead.");
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            localKey = Keys.hmacShaKeyFor(keyBytes);
        }
        if (localKey.getEncoded().length < 32) {
            throw new IllegalStateException("JWT secret key is too short: minimum 256 bits (32 bytes) required for HMAC-SHA256.");
        }
        this.key = localKey;
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        final String username = getUsernameFromToken(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private boolean isTokenExpired(String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }
}
