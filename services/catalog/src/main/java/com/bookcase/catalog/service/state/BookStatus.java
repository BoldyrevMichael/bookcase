package com.bookcase.catalog.service.state;

/** Готовность карточки. */
public enum BookStatus {

    /** Название и автор известны. */
    READY,

    /** Разбор не дал ни названия, ни автора: нужен человек. */
    NEEDS_REVIEW
}
