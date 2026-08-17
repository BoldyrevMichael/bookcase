package com.bookcase.ingester.parser.djvu;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.RawMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Разбор DJVU.
 *
 * <p>Формат придуман для сканов, и метаданных в нём, как правило, нет вовсе — ни названия, ни
 * автора. Поэтому здесь только проверка, что файл действительно DJVU и не оборван: всё остальное о
 * такой книге придётся узнать из имени файла, а обложку и описание потом добудет уточнение по
 * внешним источникам.
 *
 * <p>Нативных библиотек ради этого не подключается: структура файла — цепочка блоков с длиной в
 * заголовке, и прочитать её достаточно средствами платформы. Появление в образе djvulibre стоило бы
 * дороже, чем даёт.
 */
@Component
public class DjvuParser implements BookParser {

    private static final byte[] SIGNATURE = "AT&TFORM".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 16;

    @Override
    public BookFormat format() {
        return BookFormat.DJVU;
    }

    @Override
    public RawMetadata parse(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(HEADER_SIZE);
            if (header.length < HEADER_SIZE) {
                throw new UnsupportedContentException("файл DJVU оборван");
            }
            for (int i = 0; i < SIGNATURE.length; i++) {
                if (header[i] != SIGNATURE[i]) {
                    throw new UnsupportedContentException("это не файл DJVU");
                }
            }
            String kind = new String(header, 12, 4, StandardCharsets.US_ASCII);
            if (!"DJVU".equals(kind) && !"DJVM".equals(kind)) {
                throw new UnsupportedContentException("неизвестная разновидность DJVU: " + kind);
            }
            return RawMetadata.empty();
        } catch (IOException exception) {
            throw new UnsupportedContentException("файл DJVU не читается", exception);
        }
    }
}
