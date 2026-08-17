package com.bookcase.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;

/**
 * Хэш содержимого, контрольная сумма и размер, посчитанные за один проход.
 *
 * <p>Обе величины нужны по разным причинам: по SHA-256 файл опознаётся и хранится (одинаковые байты
 * не могут оказаться в двух объектах), CRC32 требуется заголовку записи в архиве, который
 * собирается без пережатия. Считать их порознь значило бы прочитать файл дважды.
 *
 * @param sha256 хэш в шестнадцатеричном виде
 * @param crc32 контрольная сумма
 * @param sizeBytes размер
 */
public record ContentDigest(String sha256, long crc32, long sizeBytes) {

    private static final int BUFFER_SIZE = 64 * 1024;

    public static ContentDigest of(InputStream content) throws IOException {
        MessageDigest sha256 = sha256Digest();
        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0;
        int read;
        while ((read = content.read(buffer)) != -1) {
            sha256.update(buffer, 0, read);
            crc32.update(buffer, 0, read);
            size += read;
        }
        return new ContentDigest(HexFormat.of().formatHex(sha256.digest()), crc32.getValue(), size);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("в этой среде выполнения нет SHA-256", exception);
        }
    }
}
