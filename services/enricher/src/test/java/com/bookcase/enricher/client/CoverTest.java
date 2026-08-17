package com.bookcase.enricher.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Обложка сравнивается по содержимому, а не по ссылке на массив. */
class CoverTest {

    private static final byte[] PICTURE = "не настоящая картинка".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("две обложки с одинаковыми байтами равны и дают одинаковый хэш")
    void equalContentMeansEqualCovers() {
        CoverDownloader.Cover first = new CoverDownloader.Cover(PICTURE, "image/jpeg");
        CoverDownloader.Cover second = new CoverDownloader.Cover(PICTURE.clone(), "image/jpeg");

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("разный тип картинки при тех же байтах — уже другая обложка")
    void contentTypeIsPartOfIdentity() {
        CoverDownloader.Cover jpeg = new CoverDownloader.Cover(PICTURE, "image/jpeg");
        CoverDownloader.Cover png = new CoverDownloader.Cover(PICTURE, "image/png");

        assertThat(jpeg).isNotEqualTo(png).isNotNull();
    }

    @Test
    @DisplayName("в журнал уходит размер, а не сами байты")
    void toStringShowsSizeInsteadOfBytes() {
        CoverDownloader.Cover cover = new CoverDownloader.Cover(PICTURE, "image/jpeg");

        assertThat(cover).hasToString("Cover[%d байт, image/jpeg]".formatted(PICTURE.length));
    }

    @Test
    @DisplayName("содержимое не разделяется с вызывающим: правка снаружи обложку не меняет")
    void contentIsCopied() {
        byte[] source = PICTURE.clone();
        CoverDownloader.Cover cover = new CoverDownloader.Cover(source, "image/jpeg");

        source[0] = 0;
        cover.content()[1] = 0;

        assertThat(cover.content()).isEqualTo(PICTURE);
    }
}
