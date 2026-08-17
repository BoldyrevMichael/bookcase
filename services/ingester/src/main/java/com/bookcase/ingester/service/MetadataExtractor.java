package com.bookcase.ingester.service;

import com.bookcase.events.BookFormat;
import com.bookcase.events.BookMetadata;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.normalizer.MetadataAssembler;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.FormatDetector;
import com.bookcase.ingester.parser.RawMetadata;
import com.bookcase.ingester.parser.filename.FilenameParser;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Извлечение метаданных из файла: опознание формата, разбор, разбор имени и сборка карточки.
 *
 * <p>Собрано вместе, потому что это одна работа, и делается она одинаково независимо от того,
 * откуда файл взялся, — из очереди, из повторной попытки или из ручного запуска.
 */
@Service
public class MetadataExtractor {

    private final FormatDetector detector;
    private final FilenameParser filenames;
    private final MetadataAssembler assembler;
    private final Map<BookFormat, BookParser> parsers = new EnumMap<>(BookFormat.class);

    public MetadataExtractor(
            FormatDetector detector,
            FilenameParser filenames,
            MetadataAssembler assembler,
            List<BookParser> availableParsers) {
        this.detector = detector;
        this.filenames = filenames;
        this.assembler = assembler;
        availableParsers.forEach(parser -> parsers.put(parser.format(), parser));
    }

    public BookMetadata extract(Path file, String originalName) {
        BookFormat format = detector.detect(file);
        BookParser parser = parsers.get(format);
        if (parser == null) {
            throw new UnsupportedContentException(
                    "разбор формата " + format + " не поддерживается");
        }
        RawMetadata embedded = parser.parse(file);
        RawMetadata fromFilename = filenames.parse(originalName);
        return assembler.assemble(format, embedded, fromFilename);
    }
}
