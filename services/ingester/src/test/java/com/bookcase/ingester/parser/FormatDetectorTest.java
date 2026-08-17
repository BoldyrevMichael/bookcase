package com.bookcase.ingester.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.TestBooks;
import com.bookcase.ingester.exception.UnsupportedContentException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Опознание формата.
 *
 * <p>Расширение файла в этих проверках намеренно врёт или отсутствует: именно так и бывает в живой
 * библиотеке, и решение должно приниматься по содержимому.
 */
class FormatDetectorTest {

    private final FormatDetector detector = new FormatDetector();

    @TempDir private Path directory;

    @Test
    @DisplayName("книга опознаётся по содержимому, даже когда расширение не то")
    void formatComesFromContent() {
        assertThat(detect("книга.txt", TestBooks.epub())).isEqualTo(BookFormat.EPUB);
        assertThat(detect("книга", TestBooks.djvu())).isEqualTo(BookFormat.DJVU);
        assertThat(detect("книга.epub", TestBooks.pdf())).isEqualTo(BookFormat.PDF);
        assertThat(detect("книга.xml", TestBooks.fb2())).isEqualTo(BookFormat.FB2);
        assertThat(detect("книга.dat", TestBooks.gutenbergText())).isEqualTo(BookFormat.TXT);
    }

    @Test
    @DisplayName("документ Word под видом книги отвергается понятной причиной")
    void wordDocumentIsRejected() {
        assertThatThrownBy(() -> detect("Лекции.doc", TestBooks.wordDocument()))
                .isInstanceOf(UnsupportedContentException.class)
                .hasMessageContaining("Word");
    }

    @Test
    @DisplayName("изображение и архив с исходниками — не книги")
    void junkIsRejected() {
        assertThatThrownBy(() -> detect("обложка.jpg", TestBooks.jpeg()))
                .isInstanceOf(UnsupportedContentException.class)
                .hasMessageContaining("изображение");
        assertThatThrownBy(() -> detect("9781782176466-master.zip", TestBooks.sourceArchive()))
                .isInstanceOf(UnsupportedContentException.class)
                .hasMessageContaining("архив");
    }

    private BookFormat detect(String name, byte[] content) {
        return detector.detect(file(name, content));
    }

    private Path file(String name, byte[] content) {
        return TestBooks.toFile(directory, name, content);
    }
}
