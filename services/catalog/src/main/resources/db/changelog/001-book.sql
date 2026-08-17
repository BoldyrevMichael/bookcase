--liquibase formatted sql

--changeset bookcase:000-extensions
--comment Расширения, на которых держится поиск
-- Поиск по части слова и с опечатками работает на триграммах, снятие ударений —
-- на unaccent. Если расширение уже поставлено (так делает первичная настройка
-- стенда), запрос ничего не делает и прав не требует.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

--changeset bookcase:001-book
--comment Карточки книг, авторы, темы и подборки
-- Все таблицы размечены владельцем, и индексы начинаются с него: запросов
-- без владельца в личной библиотеке не бывает.
CREATE TABLE book (
    id            uuid         PRIMARY KEY,
    owner_id      varchar(64)  NOT NULL,
    sha256        char(64)     NOT NULL,
    original_name varchar(512) NOT NULL,
    format        varchar(8)   NOT NULL,
    title         text,
    year          int,
    language      char(2),
    isbn          varchar(13),
    series        text,
    series_number int,
    publisher     text,
    -- NEEDS_REVIEW значит «нужен человек»: ни названия, ни автора не нашлось.
    -- Придумывать их за пользователя система не станет.
    status        varchar(16)  NOT NULL,
    -- Полка — состояние чтения, у книги ровно одно значение.
    shelf         varchar(16)  NOT NULL DEFAULT 'NONE',
    favorite      boolean      NOT NULL DEFAULT false,
    -- Откуда взялось каждое поле. Нужно, чтобы уточнение из внешних источников
    -- дописывало пустое и не трогало исправленное человеком.
    sources       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    search        tsvector,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    -- Один файл у одного владельца — одна карточка.
    CONSTRAINT uq_book_owner_file UNIQUE (owner_id, sha256)
);

-- Постраничность идёт по ключу «дата и идентификатор», а не по смещению: при
-- смещении база на глубоких страницах читает и выбрасывает всё, что было раньше.
CREATE INDEX ix_book_owner_created ON book (owner_id, created_at DESC, id DESC);
CREATE INDEX ix_book_owner_title ON book (owner_id, title, id);
CREATE INDEX ix_book_search ON book USING gin (search);
-- Поиск по части слова и с опечатками — по триграммам.
CREATE INDEX ix_book_title_trgm ON book USING gin (title gin_trgm_ops);

CREATE TABLE author (
    id       uuid        PRIMARY KEY,
    owner_id varchar(64) NOT NULL,
    name     text        NOT NULL,
    CONSTRAINT uq_author_owner_name UNIQUE (owner_id, name)
);

CREATE TABLE book_author (
    book_id   uuid NOT NULL REFERENCES book (id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES author (id) ON DELETE CASCADE,
    position  int  NOT NULL,
    PRIMARY KEY (book_id, author_id)
);
CREATE INDEX ix_book_author_author ON book_author (author_id);

-- Тема отвечает на вопрос «о чём книга». Словарь плоский и растёт по мере
-- надобности: у книги тем несколько, а дерево предполагает одно место, и книга
-- сразу про Java и Docker в него не укладывается. Понадобится иерархия —
-- добавится поле-родитель, не ломая данных; обратный ход дороже.
CREATE TABLE theme (
    id       uuid        PRIMARY KEY,
    owner_id varchar(64) NOT NULL,
    name     text        NOT NULL,
    CONSTRAINT uq_theme_owner_name UNIQUE (owner_id, name)
);

CREATE TABLE book_theme (
    book_id  uuid NOT NULL REFERENCES book (id) ON DELETE CASCADE,
    theme_id uuid NOT NULL REFERENCES theme (id) ON DELETE CASCADE,
    PRIMARY KEY (book_id, theme_id)
);
CREATE INDEX ix_book_theme_theme ON book_theme (theme_id);

-- Подборка отвечает на вопрос «зачем собраны вместе»: «к экзамену», «есть
-- на бумаге». Отдельного списка личных пометок нет: полка, подборки и избранное
-- закрывают их целиком, а вторая сущность меток заставляла бы каждый раз
-- выбирать, куда писать очередное слово.
CREATE TABLE collection (
    id         uuid        PRIMARY KEY,
    owner_id   varchar(64) NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_collection_owner_name UNIQUE (owner_id, name)
);

CREATE TABLE collection_book (
    collection_id uuid        NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    book_id       uuid        NOT NULL REFERENCES book (id) ON DELETE CASCADE,
    added_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, book_id)
);
CREATE INDEX ix_collection_book_book ON collection_book (book_id);

CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE processed_event;
--rollback DROP TABLE collection_book;
--rollback DROP TABLE collection;
--rollback DROP TABLE book_theme;
--rollback DROP TABLE theme;
--rollback DROP TABLE book_author;
--rollback DROP TABLE author;
--rollback DROP TABLE book;
