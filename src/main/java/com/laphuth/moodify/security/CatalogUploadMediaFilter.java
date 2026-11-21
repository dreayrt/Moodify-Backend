package com.laphuth.moodify.security;

import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.TrackService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import java.io.IOException;
import java.util.List;

/** Protect Spring's static upload handler as well as the explicit stream endpoint. */
@Component
@Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class CatalogUploadMediaFilter extends OncePerRequestFilter {
    private final org.springframework.beans.factory.ObjectProvider<TrackRepository> tracks;
    private final org.springframework.beans.factory.ObjectProvider<TrackService> serving;
    private com.laphuth.moodify.services.CatalogPreviewTickets previews;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPreviews(com.laphuth.moodify.services.CatalogPreviewTickets value) { previews=value; }

    public CatalogUploadMediaFilter(org.springframework.beans.factory.ObjectProvider<TrackRepository> tracks,org.springframework.beans.factory.ObjectProvider<TrackService> serving) { this.tracks=tracks;this.serving=serving; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/uploads/audio/")&&!request.getRequestURI().startsWith("/uploads/licenses/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=request.getRequestURI();
        if(previews!=null&&previews.valid(path,request.getParameter("preview"))) { chain.doFilter(request,response);return; }
        if(path.startsWith("/uploads/licenses/")) { response.setStatus(404);return; }
        TrackRepository repository=tracks.getIfAvailable();TrackService policy=serving.getIfAvailable();
        boolean allowed=repository!=null&&policy!=null&&repository.findByLocalPathIn(List.of(path,path.substring(1))).stream().anyMatch(policy::isTrackPubliclyAvailable);
        if(!allowed) { response.setStatus(404);return; }
        chain.doFilter(request,response);
    }
}
