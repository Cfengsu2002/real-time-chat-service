package dev.relay.chat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.api.ApiException;
import dev.relay.chat.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final ObjectMapper json;

    public JwtAuthenticationFilter(JwtService jwt, ObjectMapper json) {
        this.jwt = jwt;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            try {
                if (!authorization.startsWith("Bearer ") || authorization.length() > 4096) {
                    throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_token", "Use Bearer authentication");
                }
                ChatPrincipal principal = jwt.verify(authorization.substring(7));
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
            } catch (ApiException ex) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(response.getOutputStream(), ApiResponse.error(ex.code(), ex.getMessage()));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
