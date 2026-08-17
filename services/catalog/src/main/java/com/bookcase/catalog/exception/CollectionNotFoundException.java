package com.bookcase.catalog.exception;

/** Подборки нет — или она чужая. */
public class CollectionNotFoundException extends RuntimeException {

    public CollectionNotFoundException(String id) {
        super("подборка " + id + " не найдена");
    }
}
