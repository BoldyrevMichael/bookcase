#!/bin/sh
# Корзины хранилища. Заводятся заранее: сервис storage работает с готовыми
# корзинами и правом их создавать не наделён.
#
#   books   — оригиналы книг, имя объекта = SHA-256 содержимого;
#   covers  — обложки, полученные из файла или от внешнего источника;
#   exports — собранные архивы. Живут сутки: это результат, который можно
#             построить заново, и держать его дольше незачем.
set -eu

MASTER="${SEAWEEDFS_MASTER:-seaweedfs:9333}"

weed shell -master="$MASTER" <<'COMMANDS'
s3.bucket.create -name books
s3.bucket.create -name covers
s3.bucket.create -name exports
fs.configure -locationPrefix=/buckets/exports/ -ttl=1d -apply
s3.bucket.list
COMMANDS
