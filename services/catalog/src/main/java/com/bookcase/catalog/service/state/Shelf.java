package com.bookcase.catalog.service.state;

/**
 * Полка — состояние чтения.
 *
 * <p>У книги ровно одно значение: это не метка, а положение вещей. Именно поэтому полка отдельно от
 * тем, где значений бывает несколько.
 */
public enum Shelf {
    NONE,
    READING,
    READ,
    POSTPONED
}
