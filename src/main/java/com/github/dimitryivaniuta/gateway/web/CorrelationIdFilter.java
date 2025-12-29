package com.github.dimitryivaniuta.gateway.web;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String corr = request.getHeader(RequestContextKeys.CORRELATION_ID_HEADER);
        if (corr == null || corr.isBlank()) corr = UUID.randomUUID().toString();

        MDC.put(RequestContextKeys.CORRELATION_ID_MDC_KEY, corr);
        response.setHeader(RequestContextKeys.CORRELATION_ID_HEADER, corr);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(RequestContextKeys.CORRELATION_ID_MDC_KEY);
        }
    }
}
