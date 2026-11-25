package com.dsuttz.yaba.web;

import com.dsuttz.yaba.config.YabaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Every /api/** call must carry X-Api-Key = YABA_API_SECRET. Fails closed if the secret isn't set. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public ApiKeyFilter(YabaProperties props) {
        String s = props.apiSecret();
        this.secret = s == null || s.isBlank() ? null : s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader("X-Api-Key");
        if (secret == null || given == null
                || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write(secret == null
                    ? "{\"error\":\"YABA_API_SECRET is not set on the server\"}"
                    : "{\"error\":\"Missing or wrong X-Api-Key\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
