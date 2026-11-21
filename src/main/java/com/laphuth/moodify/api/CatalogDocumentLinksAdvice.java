package com.laphuth.moodify.api;

import java.util.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import com.laphuth.moodify.services.AudioUrlResolver;

/** Sign private document links in the admin's map-based licensing response. */
@ControllerAdvice
public class CatalogDocumentLinksAdvice implements ResponseBodyAdvice<Object> {
    public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter) { return true; }
    public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
        return request.getURI().getPath().equals("/api/admin/licensing")?sign(body):body;
    }
    private Object sign(Object value) {
        if(value instanceof Map<?,?> map) {
            Map<Object,Object> copy=new LinkedHashMap<>();
            map.forEach((key,item)->copy.put(key,"documentUrl".equals(key)&&item instanceof String path?AudioUrlResolver.resolveProtectedDocumentUrl(path):sign(item)));
            return copy;
        }
        if(value instanceof List<?> list)return list.stream().map(this::sign).toList();
        return value;
    }
}
