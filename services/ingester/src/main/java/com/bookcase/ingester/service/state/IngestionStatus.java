package com.bookcase.ingester.service.state;

/** Состояние задачи разбора. */
public enum IngestionStatus {

    /** Задача заведена, просьба разобрать отправлена. */
    QUEUED,

    /** Файл разбирается прямо сейчас. */
    RUNNING,

    /** Разбор закончен, метаданные извлечены. */
    SUCCEEDED,

    /** Разобрать не удалось; причина записана рядом. */
    FAILED
}
