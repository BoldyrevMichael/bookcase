package com.bookcase.ingester.parser;

import com.bookcase.events.BookFormat;
import java.nio.file.Path;

/** Разбор одного формата. */
public interface BookParser {

    BookFormat format();

    /**
     * Читает метаданные из файла.
     *
     * @throws com.bookcase.ingester.exception.UnsupportedContentException если файл испорчен
     *     настолько, что читать нечего
     */
    RawMetadata parse(Path file);
}
