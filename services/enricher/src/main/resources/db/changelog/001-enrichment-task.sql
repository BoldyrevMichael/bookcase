--liquibase formatted sql

--changeset bookcase:001-enrichment-task
--comment Очередь уточнения метаданных, кэш ответов справочников и учёт обработанных событий

-- Расписание уточнения живёт здесь, а не в Kafka. Причина простая: ждать приходится
-- часами. Суточная квота внешнего справочника исчерпывается за сотню книг, и если
-- держать очередь в топике, потребитель либо остановит партицию на время ожидания,
-- либо будет перекладывать одни и те же сообщения по кругу. Топик доставляет повод
-- («книга появилась»), а когда именно спрашивать справочник — записано в таблице.
CREATE TABLE enrichment_task (
    -- Одна книга — одна задача: повторное событие о той же книге не создаёт вторую.
    book_id         uuid         PRIMARY KEY,
    owner_id        varchar(64)  NOT NULL,
    -- Снимок того, что известно о книге на момент появления. По этим полям строится
    -- запрос к справочнику и с ними же сверяется найденное.
    title           text,
    authors         text,
    isbn            varchar(13),
    year            int,
    status          varchar(16)  NOT NULL,
    attempts        int          NOT NULL DEFAULT 0,
    -- Когда пробовать в следующий раз. Пауза растёт с каждой неудачей, а при
    -- исчерпанной квоте отодвигается на завтра.
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_failure    text,
    provider        varchar(32),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

-- Выборка «что пора спросить». Частичный индекс: завершённые задачи в ней не нужны,
-- а их со временем становится подавляющее большинство.
CREATE INDEX ix_enrichment_task_due
    ON enrichment_task (next_attempt_at)
    WHERE status = 'WAITING';

-- Сколько книг ждёт уточнения — показатель, который выводится наружу метрикой.
CREATE INDEX ix_enrichment_task_owner ON enrichment_task (owner_id, status);

-- Ответы справочников. Кэшируются и отрицательные тоже: «по этому запросу ничего нет»
-- — такой же ответ, и переспрашивать его назавтра значит тратить квоту впустую.
CREATE TABLE provider_response (
    provider      varchar(32) NOT NULL,
    -- Хэш нормализованного запроса: сам запрос бывает длинным, а искать нужно по точному
    -- совпадению.
    request_hash  char(64)    NOT NULL,
    request_text  text        NOT NULL,
    found         boolean     NOT NULL,
    payload       jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, request_hash)
);

-- Старые ответы вычищаются по возрасту: справочники дополняются, и вечный кэш означал бы,
-- что появившаяся в справочнике книга у нас так и останется ненайденной.
CREATE INDEX ix_provider_response_age ON provider_response (created_at);

-- Доставка «хотя бы один раз» приносит повторы. Обработанные события помечаются, чтобы
-- одна и та же книга не заводила задачу дважды.
CREATE TABLE processed_event (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE processed_event;
--rollback DROP TABLE provider_response;
--rollback DROP TABLE enrichment_task;
