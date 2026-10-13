package com.agentplatform.trigger.http;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ProblemHandler extends ResponseEntityExceptionHandler {
    @Override
    protected org.springframework.http.ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, org.springframework.http.HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            default -> "REQUEST_REJECTED";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        problem.setProperty("retryable", false);
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }
}
