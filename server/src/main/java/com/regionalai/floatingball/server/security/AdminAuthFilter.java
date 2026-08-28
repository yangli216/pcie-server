package com.regionalai.floatingball.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.service.AdminTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final AdminTokenService adminTokenService;
    private final ObjectMapper objectMapper;
    private final BbpStatisticsScope statisticsScope;

    public AdminAuthFilter(AdminTokenService adminTokenService,
                           ObjectMapper objectMapper,
                           BbpStatisticsScope statisticsScope) {
        this.adminTokenService = adminTokenService;
        this.objectMapper = objectMapper;
        this.statisticsScope = statisticsScope;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/admin/api/")
            || "/admin/api/auth/login".equals(uri)
            || "/admin/api/auth/capabilities".equals(uri)
            || "/admin/api/auth/bbp/login".equals(uri)
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("admin auth failed: missing or invalid authorization header. uri={}", request.getRequestURI());
            writeUnauthorized(response, request, "缺少管理员令牌");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        AdminCurrentUser user = adminTokenService.parse(token);
        if (user == null) {
            log.warn("admin auth failed: invalid or expired token. uri={}", request.getRequestURI());
            writeUnauthorized(response, request, "管理员令牌无效或已过期");
            return;
        }
        if (statisticsScope.isStatisticsOnly(user)
            && !statisticsScope.isAllowedStatisticsApi(request.getRequestURI())) {
            log.warn("admin auth failed: organization analyst attempted forbidden API. uri={}, bbpUserId={}, orgId={}",
                request.getRequestURI(), user.getBbpUserId(), user.getBbpOrgId());
            writeForbidden(response, request, "机构统计账号无权访问该后台功能");
            return;
        }

        try {
            AdminContextHolder.set(user);
            filterChain.doFilter(request, response);
        } finally {
            AdminContextHolder.clear();
        }
    }

    private void writeForbidden(HttpServletResponse response,
                                HttpServletRequest request,
                                String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error("AUTH-403", message, RequestIdUtils.resolve(request))
        ));
    }

    private void writeUnauthorized(HttpServletResponse response,
                                   HttpServletRequest request,
                                   String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error("AUTH-401", message, RequestIdUtils.resolve(request))
        ));
    }
}
