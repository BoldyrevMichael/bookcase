package com.bookcase.catalog.exception;

/** Книги нет — или она есть, но чужая. Снаружи эти случаи неразличимы намеренно. */
public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(String id) {
        super("книга " + id + " не найдена");
    }
}
