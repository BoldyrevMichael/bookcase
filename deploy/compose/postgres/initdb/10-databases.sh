#!/bin/sh
# Выполняется один раз, при первом запуске пустого тома.
#
# У каждого сервиса своя база и своя роль — владелец этой базы. Роль не имеет
# прав на чужие базы, поэтому сервис не дотянется до чужих таблиц даже по
# ошибке в настройках, а вынос базы в отдельный экземпляр сводится к смене
# адреса подключения.
set -eu

for service in catalog storage ingester enricher keycloak; do
    echo "создаётся роль и база $service"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
        CREATE ROLE $service LOGIN PASSWORD '$SERVICE_DB_PASSWORD';
        CREATE DATABASE $service OWNER $service;
        REVOKE ALL ON DATABASE $service FROM PUBLIC;
SQL
done

# Поиск в каталоге опирается на встроенный полнотекстовый поиск и на pg_trgm
# (опечатки и поиск по части слова). Расширение ставится суперпользователем,
# поэтому здесь, а не миграцией сервиса.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname catalog <<'SQL'
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    CREATE EXTENSION IF NOT EXISTS unaccent;
SQL
