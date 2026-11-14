package com.algotradehub.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidate = request.getHeader("X-Correlation-ID");
        String id;
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            id = candidate;
        } else {
            id = UUID.randomUUID().toString();
        }
        request.setAttribute("correlationId", id);
        response.setHeader("X-Correlation-ID", id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
