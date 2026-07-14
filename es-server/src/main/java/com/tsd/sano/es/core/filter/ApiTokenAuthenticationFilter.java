package com.tsd.sano.es.core.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.constant.ApiTokenConstants;
import com.tsd.sano.es.core.result.ResultVO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部 API Token 鉴权过滤器。
 *
 * <p>过滤器在 Controller 前执行，统一保护所有业务接口；当前使用固定 Token 白名单。
 * Token 可通过请求头或 URL 参数 {@code token} 提供，后续可在此处替换为统一认证实现。</p>
 *
 * @author lxw
 */
@Component
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthenticationFilter.class);

    /**
     * 用于输出统一 JSON 响应的 Spring ObjectMapper。
     */
    private final ObjectMapper objectMapper;

    public ApiTokenAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 token，校验成功后继续执行后续过滤器与 Controller。
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestUri = request.getRequestURI();
        // 浏览器跨域预检和常量定义的公开路径不需要业务 Token。
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || ApiTokenConstants.PUBLIC_PATHS.contains(requestUri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 请求头优先；GET 请求可使用同名 URL 参数，便于简单内部调用。
        String tokenValue = request.getHeader(ApiTokenConstants.TOKEN_NAME);
        if (StringUtils.isBlank(tokenValue)) {
            tokenValue = request.getParameter(ApiTokenConstants.TOKEN_NAME);
        }
        if (StringUtils.isBlank(tokenValue)) {
            writeUnauthorized(response, request, requestUri, "token missing or invalid");
            return;
        }

        // Token 仅用于白名单校验，日志中不记录 Token 原文。
        String token = tokenValue.trim();
        if (StringUtils.isBlank(token) || !ApiTokenConstants.VALID_TOKENS.contains(token)) {
            writeUnauthorized(response, request, requestUri, "token not accepted");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 记录不含敏感信息的鉴权失败日志，并返回统一未授权响应。
     */
    private void writeUnauthorized(HttpServletResponse response, HttpServletRequest request,
                                   String requestUri, String reason) throws IOException {
        log.warn("===> ES-Api unauthorized. method={}, uri={}, remoteAddr={}, reason={}",
                request.getMethod(), requestUri, request.getRemoteAddr(), reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ResultVO.of(HttpServletResponse.SC_UNAUTHORIZED, "未授权"));
    }
}
