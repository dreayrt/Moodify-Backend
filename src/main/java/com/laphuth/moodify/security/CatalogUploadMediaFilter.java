package com.laphuth.moodify.security;

import com.laphuth.moodify.services.CatalogPreviewTickets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Copyright contracts are only served with a short-lived staff preview ticket. */
@Component
@Order(Integer.MAX_VALUE)
public class CatalogUploadMediaFilter extends OncePerRequestFilter {
    private CatalogPreviewTickets previews;

    @Autowired(required = false)
    public void setPreviews(CatalogPreviewTickets value) {
        this.previews = value;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/uploads/licenses/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (previews != null && previews.valid(path, request.getParameter("preview"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(404);
    }
}
