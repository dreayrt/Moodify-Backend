package com.laphuth.moodify.services;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Short-lived, exact-path previews for authenticated studio/moderation responses. */
@Component
public class CatalogPreviewTickets {
    private final byte[] key;
    public CatalogPreviewTickets(@Value("${security.jwt.secret}") String secret) { key=secret.getBytes(StandardCharsets.UTF_8); }
    private String signature(String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("catalog-preview:"+value).getBytes(StandardCharsets.UTF_8)));
        } catch(Exception error) { throw new IllegalStateException("Preview signing unavailable",error); }
    }
    public String sign(String path) {
        String expiry=Long.toString(System.currentTimeMillis()/1000+300);
        return expiry+"."+signature(expiry+":"+path);
    }
    public boolean valid(String path,String ticket) {
        if(ticket==null||ticket.length()>160)return false;
        try {
            String[] parts=ticket.split("\\.",2);long expiry=Long.parseLong(parts[0]),now=System.currentTimeMillis()/1000;
            return parts.length==2 && expiry>=now && expiry<=now+300 && MessageDigest.isEqual(
                signature(parts[0]+":"+path).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII));
        } catch(RuntimeException error) { return false; }
    }
    public String previewForCurrentStaff(String url,String path) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean staff=authentication!=null&&authentication.isAuthenticated()&&authentication.getAuthorities().stream()
            .anyMatch(a->java.util.Set.of("ROLE_ADMIN","ROLE_MODERATOR","ROLE_CONTENT_LEAD").contains(a.getAuthority()));
        return staff ? url+(url.contains("?")?"&":"?")+"preview="+sign(path) : url;
    }
}
