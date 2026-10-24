package com.agentplatform.trigger.http;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ProblemHandler extends ResponseEntityExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.files.FileFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> fileFailure(com.agentplatform.domain.files.FileFailure failure) {
        if(responseCommitted())return null;
        var problem=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),"The file operation could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:"+failure.getMessage().toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        problem.setProperty("code",failure.getMessage());problem.setProperty("traceId",com.agentplatform.types.RequestTrace.current().toString());
        problem.setProperty("retryable",false);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(problem);
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.workspace.WorkspaceFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> workspaceFailure(com.agentplatform.domain.workspace.WorkspaceFailure failure) {
        var problem=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),"The workspace operation could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:"+failure.code().toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        problem.setProperty("code",failure.code());problem.setProperty("traceId",com.agentplatform.types.RequestTrace.current().toString());
        problem.setProperty("retryable",failure.status()==503);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(problem);
    }
    private boolean responseCommitted() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getResponse() != null && servlet.getResponse().isCommitted();
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> streamCapacity(org.springframework.core.task.TaskRejectedException failure) {
        return chatFailure(new com.agentplatform.domain.chat.ChatFailure(503,"STREAM_CAPACITY_EXCEEDED"));
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.chat.ChatFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> chatFailure(com.agentplatform.domain.chat.ChatFailure failure) {
        if (responseCommitted()) return null;
        var problem=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()),"The chat request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:"+failure.code().toLowerCase(java.util.Locale.ROOT).replace('_','-')));
        com.agentplatform.types.FailureRecorder.request(failure.code(),com.agentplatform.types.RequestTrace.current());problem.setProperty("code",failure.code());problem.setProperty("traceId",com.agentplatform.types.RequestTrace.current().toString());problem.setProperty("retryable",failure.status()==503||failure.status()==429);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(problem);
    }
    @org.springframework.web.bind.annotation.ExceptionHandler(com.agentplatform.domain.identity.IdentityFailure.class)
    org.springframework.http.ResponseEntity<ProblemDetail> identityFailure(com.agentplatform.domain.identity.IdentityFailure failure) {
        if (responseCommitted()) return null;
        var problem = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()), "The identity request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:" + failure.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        com.agentplatform.types.FailureRecorder.request(failure.code(),com.agentplatform.types.RequestTrace.current());
        problem.setProperty("code", failure.code());
        problem.setProperty("traceId", com.agentplatform.types.RequestTrace.current().toString());
        problem.setProperty("retryable", failure.status() == 503 || failure.status() == 429);
        return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control", "no-store").body(problem);
    }
    @Override
    protected org.springframework.http.ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, org.springframework.http.HttpHeaders headers,
            org.springframework.http.HttpStatusCode status,
            org.springframework.web.context.request.WebRequest request) {
        if (responseCommitted()) return null;
        if (status.value() == 400 && (exception instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || exception instanceof org.springframework.web.bind.MethodArgumentNotValidException)) {
            var servlet = ((org.springframework.web.context.request.ServletWebRequest) request).getRequest();
            String path = servlet.getRequestURI();
            boolean unknown = false;
            String field = null;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof tools.jackson.databind.exc.UnrecognizedPropertyException) unknown = true;
                if (cause instanceof tools.jackson.databind.DatabindException mapping && !mapping.getPath().isEmpty()) {
                    field = mapping.getPath().getFirst().getPropertyName();
                }
            }
            org.springframework.http.ResponseEntity<ProblemDetail> response;
            if (path.equals("/api/v1/auth/login")) {
                response = identityFailure(new com.agentplatform.domain.identity.IdentityFailure(400, "INVALID_LOGIN_REQUEST"));
            } else if (path.endsWith("/uploads") || path.startsWith("/api/v1/uploads") || path.startsWith("/api/v1/files")) {
                response = fileFailure(new com.agentplatform.domain.files.FileFailure(400, unknown ? "UNKNOWN_REQUEST_FIELD" : "INVALID_UPLOAD_REQUEST"));
            } else if (path.startsWith("/api/v1/workspaces")) {
                response = workspaceFailure(new com.agentplatform.domain.workspace.WorkspaceFailure(400, unknown ? "UNKNOWN_REQUEST_FIELD" : "INVALID_WORKSPACE_NAME"));
            } else {
                String error = unknown ? "UNKNOWN_REQUEST_FIELD" : path.endsWith("/model-credentials/deepseek") ? "INVALID_CREDENTIAL"
                        : "title".equals(field) ? "INVALID_TITLE" : "INVALID_TASK_REQUEST";
                int codeStatus = 400;
                if (unknown && path.equals("/api/v1/me/preferences")) { error = "UNSUPPORTED_PREFERENCE"; codeStatus = 422; }
                response = chatFailure(new com.agentplatform.domain.chat.ChatFailure(codeStatus, error));
            }
            return new org.springframework.http.ResponseEntity<>(response.getBody(), response.getHeaders(), response.getStatusCode());
        }
        String code = switch (status.value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            default -> "REQUEST_REJECTED";
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request could not be completed.");
        problem.setType(URI.create("urn:agentplatform:error:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        com.agentplatform.types.FailureRecorder.request(code,com.agentplatform.types.RequestTrace.current());
        problem.setProperty("code", code);
        problem.setProperty("traceId", com.agentplatform.types.RequestTrace.current().toString());
        problem.setProperty("retryable", false);
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }
}
