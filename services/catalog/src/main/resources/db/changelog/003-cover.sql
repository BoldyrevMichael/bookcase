--liquibase formatted sql

--changeset bookcase:003-cover
--comment Обложка книги, полученная из внешнего справочника

-- Хранится не картинка, а её хэш: сама обложка лежит в хранилище и адресуется этим хэшем.
-- Каталогу файлов не положено — он владеет карточками.
ALTER TABLE book ADD COLUMN cover_sha256 char(64);

--rollback ALTER TABLE book DROP COLUMN cover_sha256;
