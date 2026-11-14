package com.algotradehub.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setTitle(ex.code());
        problem.setProperty("code", ex.code());
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request fields are invalid.");
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ProblemDetail handleConflict(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A record with these values already exists or violates a data constraint.");
        problem.setTitle("DATA_CONFLICT");
        problem.setProperty("code", "DATA_CONFLICT");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

        @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure; correlationId={}", request.getAttribute("correlationId"));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
        problem.setTitle("INTERNAL_ERROR");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}