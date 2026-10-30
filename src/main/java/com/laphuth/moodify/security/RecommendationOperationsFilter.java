package com.laphuth.moodify.security;

import com.laphuth.moodify.services.RecommendationRuntime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class RecommendationOperationsFilter extends OncePerRequestFilter {
    private final RecommendationRuntime runtime;
    public RecommendationOperationsFilter(RecommendationRuntime runtime) { this.runtime = runtime; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String operation = path.equals("/api/analytics/listening-session") ? "playback" :
            path.equals("/api/playback/queues") ? "queue" :
            path.startsWith("/api/me/personalized-playlists") ? "mix" :
            path.equals("/api/recommendations/events") ? "events" :
            path.startsWith("/api/recommendations/") ? "recommendation" : null;
        if (operation == null) { chain.doFilter(request,response); return; }
        long start = System.nanoTime();
        boolean failed = true;
        try { chain.doFilter(request,response); failed = false; }
        finally { runtime.observe(operation, failed ? 500 : response.getStatus(), System.nanoTime() - start); }
    }
}
