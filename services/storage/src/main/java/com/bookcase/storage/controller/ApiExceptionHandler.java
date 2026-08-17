package com.bookcase.storage.controller;

import com.bookcase.storage.exception.StoredFileNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Отказы в понятном виде. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Чужой файл отвечает тем же, чем несуществующий.
     *
     * <p>Разные ответы позволили бы по одному запросу узнать, что такие байты в хранилище есть — а
     * это уже сведения о чужой библиотеке.
     */
    @ExceptionHandler(StoredFileNotFoundException.class)
    public ProblemDetail handleNotFound(StoredFileNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Файл не найден");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    /**
     * Неподходящие данные — это отказ клиента, а не поломка сервиса.
     *
     * <p>Без этого обработчика попытка выдать книгу за обложку заканчивалась бы пятисотым ответом,
     * то есть «мы сломались» вместо «так нельзя».
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Запрос не принят");
        problem.setDetail(exception.getMessage());
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONTENT_TOO_LARGE);
        problem.setTitle("Файл слишком велик");
        problem.setDetail("Размер загружаемого файла превышает допустимый");
        return problem;
    }
}
