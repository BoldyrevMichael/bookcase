package com.bookcase.ingester.parser.txt;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.RawMetadata;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Разбор текстового файла.
 *
 * <p>Главная работа здесь — угадать кодировку. Текст на русском может лежать в UTF-8, в CP1251, в
 * KOI8-R или в CP866, и прочитанный не в той кодировке он превращается в бессмыслицу, которая потом
 * навсегда останется в карточке. Определяет кодировку ICU — та же библиотека, на которую в этом
 * вопросе полагаются браузеры.
 *
 * <p>Метаданных у обычного текста нет. Но в текстах проекта «Гутенберг», а это заметная часть
 * всего, что вообще лежит в txt, начало файла размечено строками вида «Title:» и «Author:» — их и
 * читаем.
 */
@Slf4j
@Component
public class TxtParser implements BookParser {

    private static final int PROBE_SIZE = 64 * 1024;
    private static final int HEADER_LINES = 100;
    private static final int MIN_CONFIDENCE = 10;

    @Override
    public BookFormat format() {
        return BookFormat.TXT;
    }

    @Override
    public RawMetadata parse(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] probe = input.readNBytes(PROBE_SIZE);
            Charset charset = detectCharset(probe);
            List<String> lines = new String(probe, charset).lines().limit(HEADER_LINES).toList();
            String author = header(lines, "author");
            return new RawMetadata(
                    header(lines, "title"),
                    author == null ? List.of() : List.of(author),
                    header(lines, "release date"),
                    header(lines, "language"),
                    null,
                    null,
                    null,
                    null);
        } catch (IOException exception) {
            throw new UnsupportedContentException("текстовый файл не читается", exception);
        }
    }

    private Charset detectCharset(byte[] probe) {
        CharsetDetector detector = new CharsetDetector();
        detector.setText(probe);
        CharsetMatch match = detector.detect();
        if (match == null || match.getConfidence() < MIN_CONFIDENCE) {
            log.debug("кодировка не определена уверенно, читаем как UTF-8");
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(match.getName());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
            log.debug("кодировка {} не поддерживается, читаем как UTF-8", match.getName());
            return StandardCharsets.UTF_8;
        }
    }

    private String header(List<String> lines, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }
}
