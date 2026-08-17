package com.bookcase.catalog.exception;

/** Задачи выгрузки нет — или она чужая. */
public class ExportNotFoundException extends RuntimeException {

    public ExportNotFoundException(String id) {
        super("задача выгрузки " + id + " не найдена");
    }
}
