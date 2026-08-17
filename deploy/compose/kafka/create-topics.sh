#!/bin/sh
# Топики конвейера. Заводятся явно: автосоздание у брокера выключено, чтобы
# опечатка в имени кончалась ошибкой, а не топиком с одной партицией.
#
# Число партиций выбирается один раз и наперёд: потребителей в группе не может
# быть больше, чем партиций, а увеличивать их потом нельзя без потери порядка
# событий внутри ключа. Двенадцать — у разбора файлов, единственного места,
# которое реально упирается в процессор.
#
# Повторы и отказы:
#   <топик>.<потребитель>.retry — повтор с нарастающей паузой; заводится только
#       там, где работа долгая или зависит от чужой доступности, иначе повтор
#       дешевле сделать на месте, не занимая партицию;
#   <топик>.<потребитель>.dlt   — то, что не вышло совсем; заводится у каждого
#       потребителя. Имя потребителя в названии не для красоты: у одного топика
#       читателей может быть несколько, и складывать их отказы в общую корзину
#       значит потерять, чей это отказ и кому его разбирать.
set -eu

BOOTSTRAP="${BOOTSTRAP_SERVER:-kafka:9092}"

WEEK=604800000
MONTH=2592000000

create() {
    name="$1"
    partitions="$2"
    retention="$3"
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
        --create --if-not-exists \
        --topic "$name" \
        --partitions "$partitions" \
        --replication-factor 1 \
        --config retention.ms="$retention"
}

# Приём книги: запрос на разбор → извлечённые метаданные → карточка заведена.
create book.ingestion.requested                12 "$WEEK"
create book.ingestion.requested.ingester.retry 12 "$WEEK"
create book.ingestion.requested.ingester.dlt    1 "$MONTH"
create book.metadata.extracted                  6 "$WEEK"
create book.metadata.extracted.catalog.retry    6 "$WEEK"
create book.metadata.extracted.catalog.dlt      1 "$MONTH"
create book.added                               6 "$WEEK"
create book.added.enricher.dlt                  1 "$MONTH"
create book.ingestion.failed                    3 "$WEEK"
create book.ingestion.failed.catalog.dlt        1 "$MONTH"

# Обогащение метаданных из внешних источников.
create book.deleted                             6 "$WEEK"
create book.deleted.enricher.retry              6 "$WEEK"
create book.deleted.enricher.dlt                1 "$MONTH"
create book.enrichment.requested                6 "$WEEK"
create book.enrichment.requested.enricher.retry 6 "$WEEK"
create book.enrichment.requested.enricher.dlt   1 "$MONTH"
create book.enriched                            6 "$WEEK"
create book.enriched.catalog.retry              6 "$WEEK"
create book.enriched.catalog.dlt                1 "$MONTH"

# Фоновый экспорт библиотеки одним архивом.
create export.requested                         3 "$WEEK"
create export.requested.storage.retry           3 "$WEEK"
create export.requested.storage.dlt             1 "$MONTH"
create export.completed                         3 "$WEEK"
create export.completed.catalog.dlt             1 "$MONTH"
create export.failed                            3 "$WEEK"
create export.failed.catalog.dlt                1 "$MONTH"

echo
echo "топики стенда:"
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
