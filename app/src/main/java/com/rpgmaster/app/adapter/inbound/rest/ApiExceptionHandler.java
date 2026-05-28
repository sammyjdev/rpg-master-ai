package com.rpgmaster.app.adapter.inbound.rest;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final URI INVALID_REQUEST_TYPE = URI.create("https://rpgmaster.dev/problems/invalid-request");
    private static final URI INTERNAL_ERROR_TYPE = URI.create("https://rpgmaster.dev/problems/internal-error");
    private static final URI VALIDATION_TYPE = URI.create("https://rpgmaster.dev/problems/validation");

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid request");
        problem.setType(INVALID_REQUEST_TYPE);
        return problem;
    }

    /**
     * Triggered when {@code @Valid} on a {@code @RequestBody} fails in Spring MVC.
     * In WebFlux the equivalent is {@link WebExchangeBindException} below.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        List<String> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());
        return validationProblem(violations);
    }

    /**
     * WebFlux equivalent of {@link MethodArgumentNotValidException} — raised when
     * a reactive {@code @RequestBody} fails Bean Validation.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleWebExchangeValidation(WebExchangeBindException exception) {
        List<String> violations = exception.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());
        return validationProblem(violations);
    }

    /**
     * Triggered when {@code @Validated} on a method parameter (not a request body) fails,
     * e.g. validation in {@code @ConfigurationProperties} constructor at startup or
     * {@code @PathVariable} / {@code @RequestParam} constraints.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        List<String> violations = exception.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toList());
        return validationProblem(violations);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleInternal(IllegalStateException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        problem.setTitle("Internal server error");
        problem.setType(INTERNAL_ERROR_TYPE);
        return problem;
    }

    private ProblemDetail validationProblem(List<String> violations) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed");
        problem.setTitle("Validation error");
        problem.setType(VALIDATION_TYPE);
        problem.setProperty("violations", violations);
        return problem;
    }
}
