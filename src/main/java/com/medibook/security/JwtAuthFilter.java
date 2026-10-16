package com.medibook.security;

import com.medibook.entity.UserAccount;
import com.medibook.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Runs on every request. If a valid Bearer token is present, it loads the account FROM THE DATABASE
 * (so deactivated users and logged-out tokens stop working immediately) and sets the security context.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                Long id = Long.valueOf(claims.getSubject());
                UserAccount u = users.findById(id).orElse(null);
                Integer tv = claims.get("tv", Integer.class);
                if (u != null && u.isActive() && tv != null && tv == u.getTokenVersion()) {
                    CurrentUser principal = new CurrentUser(u.getId(), u.getEmail(), u.getFullName(), u.getRole());
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // Expired / tampered / malformed token: leave the request unauthenticated -> 401 from the entry point.
            }
        }
        chain.doFilter(req, res);
    }
}
