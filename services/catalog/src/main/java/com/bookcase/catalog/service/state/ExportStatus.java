package com.bookcase.catalog.service.state;

/** Состояние задачи выгрузки. */
public enum ExportStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
