package com.bookcase.ingester.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Просьба разобрать уже загруженный файл.
 *
 * @param sha256 содержимое файла в хранилище
 * @param originalName имя, под которым файл загрузили; для части форматов это единственный источник
 *     сведений о книге
 */
public record IngestionRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256,
        @NotBlank @Size(max = 512) String originalName) {}
