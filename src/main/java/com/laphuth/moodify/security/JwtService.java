package com.laphuth.moodify.security;

import com.laphuth.moodify.entities.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {
    private final String secret;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtService(
        @Value("${security.jwt.secret}") String secret,
        @Value("${security.jwt.access-token-expiration}") long accessTokenExpiration,
        @Value("${security.jwt.refresh-token-expiration}") long refreshTokenExpiration
    ) {
        this.secret = secret;
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String generateAccessToken(User currentUser) {
        return generateToken(currentUser, TokenType.ACCESS, accessTokenExpiration);
    }

    public String generateRefreshToken(User currentUser) {
        return generateToken(currentUser, TokenType.REFRESH, refreshTokenExpiration);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public TokenType extractTokenType(String token) {
        String tokenType = extractClaim(token, claims -> claims.get("tokenType", String.class));
        return TokenType.valueOf(tokenType);
    }

    public boolean isTokenValid(String token, String expectedUsername, TokenType expectedType) {
        String username = extractUsername(token);
        return username.equals(expectedUsername)
            && extractTokenType(token) == expectedType
            && !isTokenExpired(token);
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpiration / 1000;
    }

    private String generateToken(User currentUser, TokenType tokenType, long expirationMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", currentUser.getId());
        claims.put("role", currentUser.getRole().name());
        claims.put("status", currentUser.getStatus().name());
        claims.put("tokenType", tokenType.name());

        Instant now = Instant.now();
        Instant expirationTime = now.plusMillis(expirationMillis);

        return Jwts.builder()
            .claims(claims)
            .subject(currentUser.getUsername())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expirationTime))
            .signWith(getSigningKey())
            .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration / 1000;
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();

        return claimsResolver.apply(claims);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
