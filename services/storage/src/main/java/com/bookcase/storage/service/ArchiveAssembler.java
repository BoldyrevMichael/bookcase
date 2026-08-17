package com.bookcase.storage.service;

import com.bookcase.storage.dto.StoredFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

/**
 * Сборка архива.
 *
 * <p>Записи кладутся без пережатия. Книги — это уже сжатые форматы: EPUB и FB2-архивы, PDF со
 * сжатыми потоками, DJVU. Пережимать их значит потратить процессорное время и получить тот же
 * размер, а иногда и больший. Взамен архив собирается со скоростью чтения из хранилища.
 *
 * <p>Цена такого выбора — заголовок каждой записи обязан нести размер и контрольную сумму заранее,
 * до того как байты пойдут в поток. Обе величины посчитаны при загрузке файла и лежат в базе,
 * поэтому собрать архив можно, ни разу не прочитав файл лишний раз.
 *
 * <p>Куда пишется архив, сборщику неизвестно: это может быть ответ на запрос или временный файл,
 * который потом уедет в хранилище.
 */
@Service
public class ArchiveAssembler {

    private final FileStorageService files;

    public ArchiveAssembler(FileStorageService files) {
        this.files = files;
    }

    public void assemble(List<StoredFile> entries, OutputStream target) throws IOException {
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream archive = new ZipOutputStream(target)) {
            archive.setMethod(ZipOutputStream.STORED);
            for (StoredFile file : entries) {
                archive.putNextEntry(entryFor(file, uniqueName(file.originalName(), usedNames)));
                try (InputStream content = files.open(file)) {
                    content.transferTo(archive);
                }
                archive.closeEntry();
            }
        }
    }

    private static ZipEntry entryFor(StoredFile file, String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.sizeBytes());
        entry.setCompressedSize(file.sizeBytes());
        entry.setCrc(file.crc32());
        return entry;
    }

    /**
     * Одинаковые имена в архиве недопустимы, а разные книги вполне могут называться одинаково — к
     * повторам приписывается номер.
     */
    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int number = 2; ; number++) {
            String candidate = base + " (" + number + ")" + extension;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }
}
