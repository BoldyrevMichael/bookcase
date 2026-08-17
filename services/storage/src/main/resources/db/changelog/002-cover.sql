--liquibase formatted sql

--changeset bookcase:002-cover
--comment Обложки, полученные из внешних справочников

-- Обложки лежат отдельно от книг и не имеют владельца. Причина в том, что это не файлы
-- пользователя: картинку уже опубликовал справочник, и одна и та же обложка приходится
-- на всех, у кого есть эта книга. Списка ссылок здесь поэтому нет — есть сам объект,
-- на который ссылаются карточки в каталоге.
CREATE TABLE cover (
    sha256       char(64)    PRIMARY KEY,
    content_type varchar(64) NOT NULL,
    size_bytes   bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

--rollback DROP TABLE cover;
