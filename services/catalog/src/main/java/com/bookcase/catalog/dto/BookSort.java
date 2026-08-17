package com.bookcase.catalog.dto;

/** Порядок выдачи. */
public enum BookSort {

    /** Недавно добавленные сверху. */
    ADDED,

    /** По названию. */
    TITLE,

    /** По совпадению со строкой поиска; без неё бессмысленен. */
    RELEVANCE
}
