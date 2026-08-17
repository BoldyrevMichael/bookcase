package com.bookcase.ingester.parser;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Определение формата по содержимому файла.
 *
 * <p>Расширение — только подсказка, и доверять ему нельзя. В корпусе, на котором это проверялось,
 * лежит файл вовсе без расширения, внутри которого начинается «AT&amp;TFORM», то есть DJVU; и
 * наоборот, встречается «книга» с расширением doc, которая на деле документ Word. Опознание по
 * первым байтам разрешает оба случая одинаково: по тому, что внутри.
 */
@Component
public class FormatDetector {

    private static final int HEADER_SIZE = 64;
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DJVU = "AT&TFORM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {0x50, 0x4b, 0x03, 0x04};
    private static final byte[] OLE2 = HexFormat.of().parseHex("d0cf11e0a1b11ae1");
    private static final byte[] JPEG = HexFormat.of().parseHex("ffd8ff");
    private static final byte[] PNG = HexFormat.of().parseHex("89504e47");
    private static final byte[] GZIP = HexFormat.of().parseHex("1f8b");
    private static final byte[] RAR = "Rar!".getBytes(StandardCharsets.US_ASCII);

    private static final String FB2_EXTENSION = ".fb2";

    /** Сколько байтов текста хватит, чтобы отличить текст от двоичного мусора. */
    private static final int TEXT_PROBE_SIZE = 4096;

    public BookFormat detect(Path file) {
        byte[] header = readHeader(file);

        if (startsWith(header, PDF)) {
            return BookFormat.PDF;
        }
        if (startsWith(header, DJVU)) {
            return BookFormat.DJVU;
        }
        if (startsWith(header, ZIP)) {
            return detectInsideZip(file);
        }
        if (startsWith(header, OLE2)) {
            throw new UnsupportedContentException(
                    "это документ Word или другой файл Microsoft Office, а не книга");
        }
        if (startsWith(header, JPEG) || startsWith(header, PNG)) {
            throw new UnsupportedContentException("это изображение, а не книга");
        }
        if (startsWith(header, GZIP) || startsWith(header, RAR)) {
            throw new UnsupportedContentException("это архив, а не книга");
        }
        if (looksLikeFictionBook(file)) {
            return BookFormat.FB2;
        }
        if (looksLikeText(file)) {
            return BookFormat.TXT;
        }
        throw new UnsupportedContentException("формат файла не опознан");
    }

    /**
     * Внутри zip может оказаться и книга, и что угодно ещё. EPUB опознаётся по обязательной первой
     * записи mimetype, упакованный FB2 — по единственному файлу с этим расширением.
     */
    private BookFormat detectInsideZip(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry mimetype = zip.getEntry("mimetype");
            if (mimetype != null) {
                try (InputStream content = zip.getInputStream(mimetype)) {
                    String declared =
                            new String(content.readAllBytes(), StandardCharsets.US_ASCII).trim();
                    if ("application/epub+zip".equals(declared)) {
                        return BookFormat.EPUB;
                    }
                }
            }
            if (zip.getEntry("META-INF/container.xml") != null) {
                return BookFormat.EPUB;
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (endsWithIgnoringCase(name, FB2_EXTENSION)) {
                    return BookFormat.FB2;
                }
            }
            throw new UnsupportedContentException("это архив, а не книга");
        } catch (IOException exception) {
            throw new UnsupportedContentException("архив испорчен и не читается", exception);
        }
    }

    private boolean looksLikeFictionBook(Path file) {
        String beginning = readText(file, TEXT_PROBE_SIZE);
        return beginning.contains("<FictionBook");
    }

    /**
     * Текстом считается то, что читается в UTF-8 или в однобайтовой кодировке и не содержит
     * управляющих символов, которых в тексте не бывает. Нулевой байт — верный признак двоичного
     * файла.
     */
    private boolean looksLikeText(Path file) {
        byte[] probe = readHeaderOfSize(file, TEXT_PROBE_SIZE);
        if (probe.length == 0) {
            return false;
        }
        for (byte value : probe) {
            int unsigned = value & 0xff;
            boolean control =
                    unsigned < 0x20 && unsigned != '\t' && unsigned != '\n' && unsigned != '\r';
            if (control) {
                return false;
            }
        }
        return true;
    }

    private String readText(Path file, int size) {
        return new String(readHeaderOfSize(file, size), StandardCharsets.UTF_8);
    }

    private byte[] readHeader(Path file) {
        return readHeaderOfSize(file, HEADER_SIZE);
    }

    private byte[] readHeaderOfSize(Path file, int size) {
        try (InputStream input = Files.newInputStream(file)) {
            return input.readNBytes(size);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Сравнение без приведения регистра: приведение сместило бы буквы в чужих алфавитах. */
    private static boolean endsWithIgnoringCase(String value, String ending) {
        return value.length() >= ending.length()
                && value.regionMatches(
                        true, value.length() - ending.length(), ending, 0, ending.length());
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        return content.length >= signature.length
                && Arrays.equals(content, 0, signature.length, signature, 0, signature.length);
    }
}
