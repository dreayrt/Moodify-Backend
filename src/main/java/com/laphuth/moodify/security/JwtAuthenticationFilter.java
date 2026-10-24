package com.laphuth.moodify.security;

import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.repositories.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import com.laphuth.moodify.services.UserDeviceService;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserDeviceService userDeviceService;

    public JwtAuthenticationFilter(
        JwtService jwtService,
        UserRepository userRepository,
        UserDeviceService userDeviceService
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userDeviceService = userDeviceService;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);
        String username;

        try {
            username = jwtService.extractUsername(token);
        } catch (JwtException | IllegalArgumentException exception) {
            filterChain.doFilter(request, response);
            return;
        }

        if (username == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        User currentUser = userRepository.findByUsername(username).orElse(null);

        if (
            currentUser != null &&
            currentUser.getStatus() == UserStatus.ACTIVE &&
            jwtService.isTokenValid(token, currentUser.getUsername(), TokenType.ACCESS)
        ) {
            String deviceUuid = request.getHeader("X-Device-Uuid");
            String path = request.getRequestURI();
            boolean isDeviceManagementPath = path != null && (
                path.startsWith("/api/auth/devices") ||
                path.startsWith("/api/auth/logout") ||
                path.startsWith("/api/subscriptions") ||
                path.startsWith("/api/payment") ||
                path.equals("/api/users/me")
            );

            // Kiểm tra ràng buộc thiết bị: nếu thiết bị gửi request đã bị REVOKED thì chặn ngay lập tức
            if (deviceUuid != null && !deviceUuid.isBlank() && !isDeviceManagementPath) {
                if (!userDeviceService.isDeviceActive(currentUser.getId(), deviceUuid)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"DEVICE_REVOKED\",\"message\":\"Thiết bị này đã bị thu hồi quyền truy cập do tài khoản đã đăng nhập trên thiết bị khác vượt quá hạn mức cho phép của gói cước. Vui lòng đăng xuất bớt thiết bị hoặc nâng cấp gói dịch vụ.\"}");
                    return;
                }
            }

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    currentUser.getUsername(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.getRole().name()))
                );

            authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
