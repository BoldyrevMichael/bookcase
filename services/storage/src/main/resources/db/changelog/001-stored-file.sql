--liquibase formatted sql

--changeset bookcase:001-stored-file
--comment Хранимый файл и ссылки на него от владельцев
-- Файл хранится один раз на всё хранилище: имя объекта — это хэш содержимого,
-- поэтому одинаковые байты не могут оказаться в двух объектах.
CREATE TABLE stored_file (
    sha256       char(64)     PRIMARY KEY,
    size_bytes   bigint       NOT NULL,
    -- Считается при загрузке вместе с хэшем: он нужен заголовку записи в архиве,
    -- который собирается без пережатия, и подсчитывать его заново значило бы
    -- прочитать всю библиотеку целиком.
    crc32        bigint       NOT NULL,
    content_type varchar(255) NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

-- Кто и под каким именем этот файл к себе положил. Список ссылок — он же счётчик:
-- отдельное число рядом со списком рано или поздно разойдётся со списком,
-- а список сам с собой разойтись не может.
CREATE TABLE file_reference (
    owner_id      varchar(64)  NOT NULL,
    sha256        char(64)     NOT NULL REFERENCES stored_file (sha256) ON DELETE CASCADE,
    original_name varchar(512) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, sha256)
);

-- Ссылки одного файла: нужны, чтобы понять, ушёл ли последний владелец.
CREATE INDEX ix_file_reference_sha256 ON file_reference (sha256);

--rollback DROP TABLE file_reference;
--rollback DROP TABLE stored_file;
