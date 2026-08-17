# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────────────────────
# Страницы библиотеки. Собирать нечего: обычные HTML, CSS и JavaScript, поэтому
# и этапов сборки здесь нет — только раздача.
#
# Основа — nginx-unprivileged: тот же nginx, но подготовленный к работе без root
# (слушает 8080, пишет свои временные файлы туда, где у него есть права). Обычный
# образ пришлось бы править вручную в трёх местах, и каждая правка держалась бы
# на том, что в следующей версии строки конфигурации не переставят.
# ─────────────────────────────────────────────────────────────────────────────
FROM nginxinc/nginx-unprivileged:1.30-alpine

ARG VERSION=0.1.0
LABEL org.opencontainers.image.title="bookcase-web" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Личная библиотека электронных книг: страницы"

COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/index.html web/book.html web/upload.html web/shelf.html web/export.html /usr/share/nginx/html/
COPY web/css/ /usr/share/nginx/html/css/
COPY web/js/ /usr/share/nginx/html/js/

# 101 — пользователь nginx в этом образе; задан явно, чтобы то же значение можно было
# указать в манифесте и не полагаться на умолчание образа.
USER 101
EXPOSE 8080
