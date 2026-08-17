package com.bookcase.ingester;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;

/**
 * Собственный корпус для тестов.
 *
 * <p>Файлы собираются здесь же, а не лежат в репозитории двоичными: так видно, что именно в них
 * записано, и правка ожиданий не требует перекладывать вложения. Большой корпус настоящих книг для
 * автотестов не годится — он не в репозитории, весит гигабайты и меняется.
 *
 * <p>Набор повторяет то, что встречается в жизни, включая неприятное: документ Word под видом
 * книги, изображение и архив с исходниками.
 */
public final class TestBooks {

    private TestBooks() {}

    /** EPUB: zip, внутри которого container.xml указывает на описание книги. */
    public static byte[] epub() {
        String container =
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf"
                              media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;
        String opf =
                """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Погружение в паттерны проектирования</dc:title>
                    <dc:creator>Александр Швец</dc:creator>
                    <dc:date>2018-05-01</dc:date>
                    <dc:language>ru</dc:language>
                    <dc:publisher>Refactoring.Guru</dc:publisher>
                    <dc:identifier>ISBN 5-93286-153-3</dc:identifier>
                    <meta name="calibre:series" content="Паттерны"/>
                    <meta name="calibre:series_index" content="2"/>
                  </metadata>
                </package>
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            // Запись mimetype обязана быть первой и лежать без сжатия — так устроен формат.
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry entry = new ZipEntry("mimetype");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            entry.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(mimetype);
            zip.closeEntry();

            zip.setMethod(ZipOutputStream.DEFLATED);
            write(zip, "META-INF/container.xml", container);
            write(zip, "OEBPS/content.opf", opf);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    /** FB2: имя автора разложено по частям — единственный формат, где гадать не нужно. */
    public static byte[] fb2() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description>
                    <title-info>
                      <book-title>Понедельник начинается в субботу</book-title>
                      <author>
                        <first-name>Аркадий</first-name>
                        <last-name>Стругацкий</last-name>
                      </author>
                      <date>1965</date>
                      <lang>ru</lang>
                      <sequence name="НИИЧАВО" number="1"/>
                    </title-info>
                    <publish-info>
                      <publisher>Детская литература</publisher>
                      <isbn>978-5-17-118366-0</isbn>
                    </publish-info>
                  </description>
                  <body><section><p>Текст книги.</p></section></body>
                </FictionBook>
                """
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdf() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDDocumentInformation information = document.getDocumentInformation();
            information.setTitle("Совершенный код");
            information.setAuthor("Steve McConnell");
            Calendar created = Calendar.getInstance();
            created.set(Calendar.YEAR, 2010);
            information.setCreationDate(created);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** DJVU: заголовок блочной структуры. Метаданных в таких файлах не бывает. */
    public static byte[] djvu() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("AT&TFORM".getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes(new byte[] {0, 0, 0, 32});
        bytes.writeBytes("DJVU".getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes("INFO".getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes(
                new byte[] {
                    0, 0, 0, 10, 0x08, 0x2e, 0x0b, 0x36, 0x18, 0x00, 0x18, 0x00, 0x2c, 0x01
                });
        return bytes.toByteArray();
    }

    /** Текст с разметкой заголовка, как в книгах проекта «Гутенберг». */
    public static byte[] gutenbergText() {
        return """
                The Project Gutenberg eBook of Alice's Adventures in Wonderland

                Title: Alice's Adventures in Wonderland

                Author: Lewis Carroll

                Release Date: 1865

                Language: English

                *** START OF THE PROJECT GUTENBERG EBOOK ***
                Alice was beginning to get very tired of sitting by her sister on the bank.
                """
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Тот же текст в CP1251: проверка того, что кодировка определяется, а не предполагается. */
    public static byte[] russianTextInCp1251() {
        String text =
                """
                Глава первая

                Вечерело. Пыльная дорога уходила за холм, и ничто не предвещало беды.
                Странники шли молча, изредка поглядывая на небо, где собирались тучи.
                """;
        return text.getBytes(Charset.forName("windows-1251"));
    }

    /** Документ Word: в корпусе такой лежит с виду как книга. */
    public static byte[] wordDocument() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(HexFormat.of().parseHex("d0cf11e0a1b11ae1"));
        bytes.writeBytes(new byte[512]);
        return bytes.toByteArray();
    }

    public static byte[] jpeg() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(HexFormat.of().parseHex("ffd8ffe0"));
        bytes.writeBytes("JFIF".getBytes(StandardCharsets.US_ASCII));
        bytes.writeBytes(new byte[64]);
        return bytes.toByteArray();
    }

    /** Архив с исходниками к книге — не книга. */
    public static byte[] sourceArchive() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            write(zip, "chapter01/main.js", "console.log('hello');");
            write(zip, "README.md", "# Исходники к книге");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return bytes.toByteArray();
    }

    public static Path toFile(Path directory, String name, byte[] content) {
        try {
            Path file = directory.resolve(name);
            Files.write(file, content);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
