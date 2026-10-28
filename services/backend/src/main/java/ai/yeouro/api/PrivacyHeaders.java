package ai.yeouro.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PrivacyHeaders extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
