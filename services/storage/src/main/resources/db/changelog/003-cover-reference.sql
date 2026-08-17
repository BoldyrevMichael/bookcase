--liquibase formatted sql

--changeset bookcase:003-cover-reference
--comment Учёт того, кто держит обложку, — иначе она остаётся навсегда

-- У обложки нет владельца, но есть держатели: карточки книг, которые её показывают.
-- Без этого списка обложка переживала бы книгу и оставалась в хранилище навсегда —
-- ровно та же беда, от которой у файлов книг спасает file_reference.
--
-- Держатель — карточка, а не владелец: у одного человека может оказаться два издания
-- одной книги с одинаковой обложкой, и удаление первого не должно лишать картинки второе.
-- Владелец записан рядом, чтобы отпустить обложку мог только он.
CREATE TABLE cover_reference (
    holder_id  uuid        PRIMARY KEY,
    sha256     char(64)    NOT NULL REFERENCES cover (sha256) ON DELETE CASCADE,
    owner_id   varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Сколько держателей у обложки: по нему решается, пора ли её убирать.
CREATE INDEX ix_cover_reference_sha ON cover_reference (sha256);

--rollback DROP TABLE cover_reference;
