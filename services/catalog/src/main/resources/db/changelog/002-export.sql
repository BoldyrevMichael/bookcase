--liquibase formatted sql

--changeset bookcase:002-export
--comment Задачи фонового экспорта библиотеки
-- Задача хранит не архив, а имя объекта: собранный архив живёт в хранилище
-- сутки, и когда он истекает, задача остаётся историей того, что делалось.
CREATE TABLE export_task (
    id             uuid        PRIMARY KEY,
    owner_id       varchar(64) NOT NULL,
    status         varchar(16) NOT NULL,
    book_count     int         NOT NULL,
    archive_key    text,
    size_bytes     bigint,
    failure_reason text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_export_task_owner ON export_task (owner_id, created_at DESC);

--rollback DROP TABLE export_task;
