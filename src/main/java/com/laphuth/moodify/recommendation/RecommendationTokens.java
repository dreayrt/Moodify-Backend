package com.laphuth.moodify.recommendation;

import static com.laphuth.moodify.recommendation.RecommendationTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RecommendationTokens {
    private final byte[] secret;
    private final ObjectMapper json = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public RecommendationTokens(@Value("${recommendations.token-secret:${security.jwt.secret}}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(TokenClaims claims) {
        try {
            String payload = ENCODER.encodeToString(json.writeValueAsBytes(claims));
            return payload + "." + ENCODER.encodeToString(sign(payload));
        } catch (Exception e) { throw new IllegalStateException("Cannot sign recommendation context", e); }
    }

    public TokenClaims verify(String token, long userId, String purpose) {
        try {
            if (token == null || token.length() > 16_384) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            TokenClaims claims = json.readValue(Base64.getUrlDecoder().decode(parts[0]), TokenClaims.class);
            if (claims.userId() != userId || !purpose.equals(claims.purpose()) ||
                claims.expiresAt() <= Instant.now().getEpochSecond()) throw new IllegalArgumentException();
            return claims;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired recommendation context");
        }
    }

    private byte[] sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
