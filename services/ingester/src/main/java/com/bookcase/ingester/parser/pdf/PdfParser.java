package com.bookcase.ingester.parser.pdf;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.RawMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;

/**
 * Разбор PDF.
 *
 * <p>Читается только словарь сведений о документе. Полезен он редко: у книг, собранных из сканов,
 * там обычно имя программы, которой сканировали, а название встречается хорошо если у каждой
 * десятой. Поэтому пустая выдача здесь — обычное дело, а не признак поломки: дальше подключится
 * разбор имени файла.
 *
 * <p>Закрытый паролем PDF считается неразбираемым сразу: подбирать пароль сервис не будет.
 */
@Component
public class PdfParser implements BookParser {

    /** Имена программ, которые пишут себя в поле «автор». Это не автор книги. */
    private static final List<String> TOOL_MARKERS =
            List.of(
                    "acrobat",
                    "scanner",
                    "ghostscript",
                    "abbyy",
                    "microsoft",
                    "pdftex",
                    "quartz",
                    "adobe");

    @Override
    public BookFormat format() {
        return BookFormat.PDF;
    }

    @Override
    public RawMetadata parse(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDDocumentInformation information = document.getDocumentInformation();
            String author =
                    looksLikeTool(information.getAuthor()) ? null : clean(information.getAuthor());
            return new RawMetadata(
                    clean(information.getTitle()),
                    author == null ? List.of() : List.of(author),
                    year(information),
                    null,
                    null,
                    null,
                    null,
                    null);
        } catch (IOException exception) {
            throw new UnsupportedContentException(
                    "файл PDF не читается или закрыт паролем", exception);
        }
    }

    private String year(PDDocumentInformation information) {
        var created = information.getCreationDate();
        return created == null ? null : String.valueOf(created.get(java.util.Calendar.YEAR));
    }

    private boolean looksLikeTool(String author) {
        if (author == null) {
            return false;
        }
        String lower = author.toLowerCase(java.util.Locale.ROOT);
        return TOOL_MARKERS.stream().anyMatch(lower::contains);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
