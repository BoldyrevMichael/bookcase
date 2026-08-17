package com.bookcase.catalog.service;

import com.bookcase.catalog.dto.CollectionView;
import com.bookcase.catalog.exception.BookNotFoundException;
import com.bookcase.catalog.exception.CollectionNotFoundException;
import com.bookcase.catalog.repository.BookRepository;
import com.bookcase.catalog.repository.CollectionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Подборки: списки книг, собранные человеком с какой-то целью. */
@Service
public class CollectionService {

    private final CollectionRepository collections;
    private final BookRepository books;

    public CollectionService(CollectionRepository collections, BookRepository books) {
        this.collections = collections;
        this.books = books;
    }

    /** Подборка с таким именем заводится один раз: второй вызов вернёт ту же. */
    public UUID create(String ownerId, String name) {
        return collections.create(ownerId, name.trim());
    }

    public List<CollectionView> findAll(String ownerId) {
        return collections.findAll(ownerId);
    }

    public void delete(UUID id, String ownerId) {
        if (!collections.delete(id, ownerId)) {
            throw new CollectionNotFoundException(id.toString());
        }
    }

    @Transactional
    public void addBook(UUID collectionId, UUID bookId, String ownerId) {
        ensureBoth(collectionId, bookId, ownerId);
        collections.addBook(collectionId, bookId);
    }

    @Transactional
    public void removeBook(UUID collectionId, UUID bookId, String ownerId) {
        ensureBoth(collectionId, bookId, ownerId);
        collections.removeBook(collectionId, bookId);
    }

    /** И подборка, и книга должны принадлежать обратившемуся; чужое для него не существует. */
    private void ensureBoth(UUID collectionId, UUID bookId, String ownerId) {
        collections
                .find(collectionId, ownerId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId.toString()));
        books.find(bookId, ownerId).orElseThrow(() -> new BookNotFoundException(bookId.toString()));
    }
}
