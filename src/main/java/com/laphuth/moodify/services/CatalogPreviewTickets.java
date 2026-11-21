package com.laphuth.moodify.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CatalogPreviewTickets {
    private final byte[] key;

    public CatalogPreviewTickets(@Value(value="${security.jwt.secret}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    private String signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("catalog-preview:" + value).getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception error) {
            throw new IllegalStateException("Preview signing unavailable", error);
        }
    }

    public String sign(String path) {
        String expiry = Long.toString(System.currentTimeMillis() / 1000L + 300L);
        return expiry + "." + this.signature(expiry + ":" + path);
    }

    public boolean valid(String path, String ticket) {
        if (ticket == null || ticket.length() > 160) {
            return false;
        }
        try {
            String[] parts = ticket.split("\\.", 2);
            long expiry = Long.parseLong(parts[0]);
            long now = System.currentTimeMillis() / 1000L;
            return parts.length == 2 && expiry >= now && expiry <= now + 300L && MessageDigest.isEqual(this.signature(parts[0] + ":" + path).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII));
        }
        catch (RuntimeException error) {
            return false;
        }
    }

    public String previewForCurrentStaff(String url, String path) {
        boolean staff;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean bl = staff = authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_MODERATOR", "ROLE_CONTENT_LEAD").contains(a.getAuthority()));
        return staff ? url + (url.contains("?") ? "&" : "?") + "preview=" + this.sign(path) : url;
    }
}
