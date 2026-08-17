package com.bookcase.catalog.controller;

import com.bookcase.catalog.exception.BookNotFoundException;
import com.bookcase.catalog.exception.CollectionNotFoundException;
import com.bookcase.catalog.exception.ExportNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Отказы в понятном виде. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Чужое отвечает тем же, чем несуществующее.
     *
     * <p>Иначе по ответу можно было бы узнать, что такая книга в системе есть, — а это уже сведения
     * о чужой библиотеке.
     */
    @ExceptionHandler({
        BookNotFoundException.class,
        CollectionNotFoundException.class,
        ExportNotFoundException.class
    })
    public ProblemDetail handleNotFound(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Не найдено");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}
