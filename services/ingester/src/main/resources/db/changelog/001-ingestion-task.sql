--liquibase formatted sql

--changeset bookcase:001-ingestion-task
--comment Задачи разбора и учёт уже обработанных событий
CREATE TABLE ingestion_task (
    id             uuid         PRIMARY KEY,
    owner_id       varchar(64)  NOT NULL,
    sha256         char(64)     NOT NULL,
    original_name  varchar(512) NOT NULL,
    status         varchar(16)  NOT NULL,
    failure_reason text,
    -- Подписанный пропуск на скачивание файла из хранилища. Разбор идёт в фоне,
    -- когда токена пользователя уже нет, а пропуск выписан на один файл одного
    -- владельца и на ограниченный срок.
    download_ticket text        NOT NULL,
    metadata       jsonb,
    attempts       int          NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);

-- Список задач владельца, свежие сверху: запросов без владельца не бывает.
CREATE INDEX ix_ingestion_task_owner ON ingestion_task (owner_id, created_at DESC);

-- Одно и то же событие может приехать повторно: так устроена доставка «хотя бы
-- один раз». Разбирать файл второй раз незачем, поэтому обработанные помечаются.
CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE processed_event;
--rollback DROP TABLE ingestion_task;
