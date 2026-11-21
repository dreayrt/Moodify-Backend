package com.laphuth.moodify.api;

import com.laphuth.moodify.services.AudioUrlResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class CatalogDocumentLinksAdvice
implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type, Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        return request.getURI().getPath().equals("/api/admin/licensing") ? this.sign(body) : body;
    }

    private Object sign(Object value) {
        if (value instanceof Map) {
            Map map = (Map)value;
            LinkedHashMap copy = new LinkedHashMap();
            map.forEach((key, item) -> {
                Object object;
                if ("documentUrl".equals(key) && item instanceof String) {
                    String path = (String)item;
                    object = AudioUrlResolver.resolveProtectedDocumentUrl(path);
                } else {
                    object = this.sign(item);
                }
                copy.put(key, object);
            });
            return copy;
        }
        if (value instanceof List) {
            List list = (List)value;
            return list.stream().map(this::sign).toList();
        }
        return value;
    }
}
