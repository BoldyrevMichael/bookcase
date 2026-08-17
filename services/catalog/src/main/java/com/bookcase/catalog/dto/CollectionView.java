package com.bookcase.catalog.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Подборка.
 *
 * @param id подборка
 * @param name имя, данное человеком
 * @param bookCount сколько книг внутри
 * @param createdAt когда заведена
 */
public record CollectionView(UUID id, String name, long bookCount, Instant createdAt) {}
