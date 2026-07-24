package com.regionalai.floatingball.server.common.exception;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String GENERIC_BUSINESS_MESSAGE = "操作处理失败，请稍后重试；如持续出现，请联系管理员查看日志";
    private static final Pattern TECHNICAL_CLASS_PATTERN = Pattern.compile("(^|\\s)(java|javax|org|com)\\.[\\w.]+");
    private static final Pattern ORACLE_ERROR_PATTERN = Pattern.compile("ORA-\\d{5}", Pattern.CASE_INSENSITIVE);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("business exception. method={}, uri={}, requestId={}, code={}, message={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            ex.getCode(),
            ex.getMessage());
        return ApiResponse.error(ex.getCode(), sanitizeBusinessMessage(ex.getMessage()), resolveRequestId(request));
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> handleConflict(ConflictException ex, HttpServletRequest request) {
        log.warn("conflict exception. method={}, uri={}, requestId={}, code={}, message={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            ex.getCode(),
            ex.getMessage());
        return ApiResponse.error(ex.getCode(), sanitizeBusinessMessage(ex.getMessage()), resolveRequestId(request));
    }

    @ExceptionHandler(UpdateRequiredException.class)
    @ResponseStatus(HttpStatus.UPGRADE_REQUIRED)
    public ApiResponse<Void> handleUpdateRequired(UpdateRequiredException ex, HttpServletRequest request) {
        log.warn("update required. method={}, uri={}, requestId={}, minSupportedVersion={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            ex.getMinSupportedVersion());
        return ApiResponse.error("UPDATE-REQUIRED", ex.getMessage(), resolveRequestId(request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().isEmpty()
            ? "参数校验失败"
            : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("validation exception. method={}, uri={}, requestId={}, message={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            message);
        return ApiResponse.error("VALIDATION-001", message, resolveRequestId(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("request body parse exception. method={}, uri={}, requestId={}, message={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            ex.getMessage());
        return ApiResponse.error("VALIDATION-002", "请求内容格式不正确，请检查后重试", resolveRequestId(request));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception ex, HttpServletRequest request) {
        log.error("unexpected exception. method={}, uri={}, requestId={}",
            request.getMethod(),
            request.getRequestURI(),
            resolveRequestId(request),
            ex);
        return ApiResponse.error("SYS-500", "后台服务处理失败，请稍后重试；如持续出现，请联系管理员并提供请求ID", resolveRequestId(request));
    }

    private String resolveRequestId(HttpServletRequest request) {
        return RequestIdUtils.resolve(request);
    }

    private String sanitizeBusinessMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return GENERIC_BUSINESS_MESSAGE;
        }
        String trimmed = message.trim();
        if (!containsTechnicalDetail(trimmed)) {
            return trimmed;
        }
        String context = extractBusinessContext(trimmed);
        return context == null
            ? GENERIC_BUSINESS_MESSAGE
            : context + "，请稍后重试；如持续出现，请联系管理员查看日志";
    }

    private boolean containsTechnicalDetail(String message) {
        String lower = message.toLowerCase();
        return ORACLE_ERROR_PATTERN.matcher(message).find()
            || TECHNICAL_CLASS_PATTERN.matcher(message).find()
            || lower.contains("sqlexception")
            || lower.contains("nullpointerexception")
            || lower.contains("stack trace")
            || lower.contains("connection refused")
            || lower.contains("sockettimeoutexception")
            || lower.contains("read timed out")
            || lower.contains("connect timed out")
            || lower.contains("broken pipe")
            || lower.contains("i/o error");
    }

    private String extractBusinessContext(String message) {
        int chineseColon = message.indexOf('：');
        int englishColon = message.indexOf(':');
        int idx;
        if (chineseColon >= 0 && englishColon >= 0) {
            idx = Math.min(chineseColon, englishColon);
        } else {
            idx = Math.max(chineseColon, englishColon);
        }
        if (idx <= 0) {
            return null;
        }
        String context = message.substring(0, idx).trim();
        return context.isEmpty() ? null : context;
    }
}
