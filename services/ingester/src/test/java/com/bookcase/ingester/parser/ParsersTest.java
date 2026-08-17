package com.bookcase.ingester.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookcase.ingester.TestBooks;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.djvu.DjvuParser;
import com.bookcase.ingester.parser.epub.EpubParser;
import com.bookcase.ingester.parser.fb2.Fb2Parser;
import com.bookcase.ingester.parser.pdf.PdfParser;
import com.bookcase.ingester.parser.txt.TxtParser;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Что каждый разборщик достаёт из своего формата. */
class ParsersTest {

    @TempDir private Path directory;

    @Test
    @DisplayName("EPUB: название, автор, дата, язык, издательство, серия и ISBN")
    void epubIsParsed() {
        RawMetadata metadata = new EpubParser().parse(file("книга.epub", TestBooks.epub()));

        assertThat(metadata.title()).isEqualTo("Погружение в паттерны проектирования");
        assertThat(metadata.authors()).containsExactly("Александр Швец");
        assertThat(metadata.date()).isEqualTo("2018-05-01");
        assertThat(metadata.language()).isEqualTo("ru");
        assertThat(metadata.publisher()).isEqualTo("Refactoring.Guru");
        assertThat(metadata.series()).isEqualTo("Паттерны");
        assertThat(metadata.seriesNumber()).isEqualTo("2");
        assertThat(metadata.isbn()).isEqualTo("ISBN 5-93286-153-3");
    }

    @Test
    @DisplayName("FB2: имя автора собирается из частей, серия и ISBN на месте")
    void fb2IsParsed() {
        RawMetadata metadata = new Fb2Parser().parse(file("книга.fb2", TestBooks.fb2()));

        assertThat(metadata.title()).isEqualTo("Понедельник начинается в субботу");
        assertThat(metadata.authors()).containsExactly("Аркадий Стругацкий");
        assertThat(metadata.date()).isEqualTo("1965");
        assertThat(metadata.language()).isEqualTo("ru");
        assertThat(metadata.series()).isEqualTo("НИИЧАВО");
        assertThat(metadata.isbn()).isEqualTo("978-5-17-118366-0");
    }

    @Test
    @DisplayName("PDF: название и автор из сведений о документе")
    void pdfIsParsed() {
        RawMetadata metadata = new PdfParser().parse(file("книга.pdf", TestBooks.pdf()));

        assertThat(metadata.title()).isEqualTo("Совершенный код");
        assertThat(metadata.authors()).containsExactly("Steve McConnell");
        assertThat(metadata.date()).isEqualTo("2010");
    }

    @Test
    @DisplayName("DJVU: метаданных нет, и это нормальный исход, а не ошибка")
    void djvuHasNoMetadata() {
        RawMetadata metadata = new DjvuParser().parse(file("книга.djvu", TestBooks.djvu()));

        assertThat(metadata.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("текст: разметка заголовка читается")
    void textHeadersAreParsed() {
        RawMetadata metadata = new TxtParser().parse(file("alice.txt", TestBooks.gutenbergText()));

        assertThat(metadata.title()).isEqualTo("Alice's Adventures in Wonderland");
        assertThat(metadata.authors()).containsExactly("Lewis Carroll");
        assertThat(metadata.language()).isEqualTo("English");
    }

    @Test
    @DisplayName("текст в CP1251 читается как текст, а не как набор вопросительных знаков")
    void cp1251IsDetected() {
        Path file = file("глава.txt", TestBooks.russianTextInCp1251());

        assertThat(new TxtParser().parse(file).isEmpty()).isTrue();
        assertThat(new FormatDetector().detect(file)).isNotNull();
    }

    @Test
    @DisplayName("FB2 в архиве: если внутри нет книги, разбор отказывает, а архив закрывается")
    void zippedFb2WithoutBookIsRejected() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
            zip.write("это не книга".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path archive = file("книга.fb2.zip", bytes.toByteArray());

        Fb2Parser parser = new Fb2Parser();
        assertThatThrownBy(() -> parser.parse(archive))
                .isInstanceOf(UnsupportedContentException.class);

        // Архив должен быть закрыт: на Windows открытый файл нельзя ни удалить, ни перезаписать,
        // а очередь разбора именно этим и занимается.
        assertThat(archive.toFile().delete()).isTrue();
    }

    private Path file(String name, byte[] content) {
        return TestBooks.toFile(directory, name, content);
    }
}
