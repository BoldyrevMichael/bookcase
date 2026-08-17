package com.bookcase.ingester.controller;

import com.bookcase.ingester.exception.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Отказы в понятном виде. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Чужая задача отвечает тем же, чем несуществующая: иначе по ответу видно, что она есть. */
    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleNotFound(TaskNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Задача не найдена");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
