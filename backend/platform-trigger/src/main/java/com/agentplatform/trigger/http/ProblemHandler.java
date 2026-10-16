package com.agentplatform.trigger.http;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ProblemHandler extends ResponseEntityExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> streamCapacity(org.springframework.core.task.TaskRejectedException failure) {
        return chatFailure(new com.agentplatform.domain.chat.ChatFailure(503,"STREAM_CAPACITY_EXCEEDED"));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.chat.ChatFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> chatFailure(com.agentplatform.domain.chat.ChatFailure failure) {
        var problem=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),"The chat request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:"+failure.code().toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        problem.setProperty("code",failure.code());problem.setProperty("traceId",UUID.randomUUID().toString());problem.setProperty("retryable",failure.status()==503||failure.status()==429);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(problem);
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.identity.IdentityFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> identityFailure(com.agentplatform.domain.identity.IdentityFailure failure) {
        var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()), "The identity request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:" + failure.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", failure.code());
        problem.setProperty("traceId", UUID.randomUUID().toString());
        problem.setProperty("retryable", failure.status() == 503 || failure.status() == 429);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control", "no-store").body(problem);
    }
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
