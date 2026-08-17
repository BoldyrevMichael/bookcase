package com.bookcase.events;

/**
 * Имена топиков.
 *
 * <p>Собраны в одном месте: имя топика — это договор между сервисами, и опечатка в нём у одной
 * стороны означает молчание, а не ошибку.
 */
public final class Topics {

    public static final String BOOK_INGESTION_REQUESTED = "book.ingestion.requested";
    public static final String BOOK_METADATA_EXTRACTED = "book.metadata.extracted";
    public static final String BOOK_INGESTION_FAILED = "book.ingestion.failed";
    public static final String BOOK_ADDED = "book.added";
    public static final String BOOK_ENRICHED = "book.enriched";
    public static final String BOOK_DELETED = "book.deleted";
    public static final String BOOK_ENRICHMENT_REQUESTED = "book.enrichment.requested";
    public static final String EXPORT_REQUESTED = "export.requested";
    public static final String EXPORT_COMPLETED = "export.completed";
    public static final String EXPORT_FAILED = "export.failed";

    /**
     * Повторы и отказы именуются с добавлением имени потребителя: у топика может быть несколько
     * читателей, и складывать их отказы в общую корзину — значит потерять, чей это отказ.
     */
    public static final String INGESTER_RETRY_SUFFIX = ".ingester.retry";

    public static final String INGESTER_DLT_SUFFIX = ".ingester.dlt";
    public static final String CATALOG_DLT_SUFFIX = ".catalog.dlt";
    public static final String ENRICHER_DLT_SUFFIX = ".enricher.dlt";
    public static final String STORAGE_RETRY_SUFFIX = ".storage.retry";
    public static final String STORAGE_DLT_SUFFIX = ".storage.dlt";

    private Topics() {}
}
