package io.trustagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trustagent.common.ApiResponse;
import io.trustagent.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 把 Spring Security 在过滤器层产生的 401/403 也输出成统一的 JSON 结构，
 * 避免前端收到一段 HTML 或空响应体后无法解析。
 */
public class JsonAuthHandlers {

    private JsonAuthHandlers() {
    }

    @Component
    public static class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

        private final ObjectMapper objectMapper;

        public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException authException) throws IOException {
            Object attribute = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
            ErrorCode code = attribute instanceof ErrorCode ec ? ec : ErrorCode.TOKEN_INVALID;
            write(objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED, code);
        }
    }

    @Component
    public static class JsonAccessDeniedHandler implements AccessDeniedHandler {

        private final ObjectMapper objectMapper;

        public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException accessDeniedException) throws IOException {
            write(objectMapper, response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
        }
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status, ErrorCode code)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.fail(code, null));
    }
}
