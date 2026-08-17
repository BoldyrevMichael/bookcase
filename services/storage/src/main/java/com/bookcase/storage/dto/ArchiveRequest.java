package com.bookcase.storage.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Что положить в архив.
 *
 * @param files хэши файлов; чужие и несуществующие приводят к отказу, а не к пропуску
 */
public record ArchiveRequest(@NotEmpty List<String> files) {

    public ArchiveRequest {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
