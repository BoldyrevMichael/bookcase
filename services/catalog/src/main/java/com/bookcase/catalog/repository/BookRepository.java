package com.bookcase.catalog.repository;

import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.dto.BookQuery;
import com.bookcase.catalog.dto.BookSort;
import com.bookcase.catalog.dto.Facets;
import com.bookcase.catalog.service.state.BookStatus;
import com.bookcase.catalog.service.state.Shelf;
import com.bookcase.events.BookFormat;
import com.bookcase.events.MetadataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Карточки книг.
 *
 * <p>Отбор строится по частям: у поиска десяток необязательных условий, и запрос на все случаи
 * разом был бы нечитаем, а база всё равно не смогла бы им воспользоваться. Условия и значения
 * собираются рядом, поэтому подставляются они значениями, а не склейкой строк.
 */
@Repository
public class BookRepository {

    /** Русская конфигурация разбирает и латиницу; для смешанной библиотеки этого достаточно. */
    private static final String TEXT_SEARCH_CONFIG = "russian";

    /**
     * Насколько похожим должно быть слово, чтобы считаться опечаткой. Измерено на «паттрены» против
     * «паттерны»: совпадение около 0.38, поэтому 0.3 такую опечатку ловит, а случайные созвучия —
     * уже нет.
     */
    private static final String TYPO_THRESHOLD = "0.3";

    /**
     * По чему книга находится. Веса расставлены по убыванию значимости: название важнее автора,
     * автор важнее серии, серия важнее издательства — искать книгу по издательству случается редко,
     * и находка по нему не должна опережать находку по названию.
     */
    private static final String SEARCH_EXPRESSION =
            "setweight(to_tsvector('{config}', coalesce(:title, '')), 'A')"
                    + " || setweight(to_tsvector('{config}', coalesce(:authors, '')), 'B')"
                    + " || setweight(to_tsvector('{config}', coalesce(:series, '')), 'C')"
                    + " || setweight(to_tsvector('{config}', coalesce(:publisher, '')), 'D')";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public BookRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Заводит карточку.
     *
     * <p>Повторное событие о том же файле ничего не удваивает: пара «владелец и содержимое» у книги
     * одна.
     *
     * @return карточка появилась только что
     */
    public boolean insert(BookCard card, String ownerId, String authorsForSearch) {
        return jdbc.sql(
                                """
                        INSERT INTO book (id, owner_id, sha256, original_name, format, title, year,
                                          language, isbn, series, series_number, publisher,
                                          cover_sha256, status, sources, search)
                        VALUES (:id, :ownerId, :sha256, :originalName, :format, :title, :year,
                                :language, :isbn, :series, :seriesNumber, :publisher, :cover,
                                :status, cast(:sources as jsonb), {search})
                        ON CONFLICT (owner_id, sha256) DO NOTHING
                        """
                                        .replace("{search}", SEARCH_EXPRESSION)
                                        .replace("{config}", TEXT_SEARCH_CONFIG))
                        .param("id", card.id())
                        .param("ownerId", ownerId)
                        .param("sha256", card.sha256())
                        .param("originalName", card.originalName())
                        .param("format", card.format().name())
                        .param("title", card.title())
                        .param("year", card.year())
                        .param("language", card.language())
                        .param("isbn", card.isbn())
                        .param("series", card.series())
                        .param("seriesNumber", card.seriesNumber())
                        .param("publisher", card.publisher())
                        .param("cover", card.coverSha256())
                        .param("status", card.status().name())
                        .param("sources", writeSources(card.sources()))
                        .param("authors", authorsForSearch)
                        .update()
                > 0;
    }

    /** Обновляет описание книги и пересобирает то, по чему она ищется. */
    public void update(UUID id, String ownerId, BookCard card, String authorsForSearch) {
        jdbc.sql(
                        """
                        UPDATE book
                        SET title = :title, year = :year, language = :language, isbn = :isbn,
                            series = :series, series_number = :seriesNumber, publisher = :publisher,
                            cover_sha256 = :cover, status = :status,
                            sources = cast(:sources as jsonb),
                            updated_at = now(), search = {search}
                        WHERE id = :id AND owner_id = :ownerId
                        """
                                .replace("{search}", SEARCH_EXPRESSION)
                                .replace("{config}", TEXT_SEARCH_CONFIG))
                .param("id", id)
                .param("ownerId", ownerId)
                .param("title", card.title())
                .param("year", card.year())
                .param("language", card.language())
                .param("isbn", card.isbn())
                .param("series", card.series())
                .param("seriesNumber", card.seriesNumber())
                .param("publisher", card.publisher())
                .param("cover", card.coverSha256())
                .param("status", card.status().name())
                .param("sources", writeSources(card.sources()))
                .param("authors", authorsForSearch)
                .update();
    }

    public Optional<BookCard> find(UUID id, String ownerId) {
        return jdbc.sql("SELECT * FROM book WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(this::mapCard)
                .optional();
    }

    public Optional<BookCard> findByFile(String sha256, String ownerId) {
        return jdbc.sql("SELECT * FROM book WHERE sha256 = :sha256 AND owner_id = :ownerId")
                .param("sha256", sha256)
                .param("ownerId", ownerId)
                .query(this::mapCard)
                .optional();
    }

    /**
     * Выдача вместе со значением, по которому шёл порядок: только база знает, чему равно совпадение
     * последней строки, а без него нельзя сказать, откуда продолжать.
     */
    public List<SearchRow> search(BookQuery query, String ownerId, Cursor cursor) {
        Filter filter = filterFor(query, ownerId);
        StringBuilder sql =
                new StringBuilder("SELECT b.* ")
                        .append(rankColumn(query))
                        .append(" FROM book b ")
                        .append(filter.joins())
                        .append(" WHERE ")
                        .append(filter.conditions());

        appendCursorCondition(sql, query, cursor, filter.parameters());
        sql.append(orderBy(query)).append(" LIMIT :limit");
        filter.parameters().put("limit", query.limit());

        BookSort sort = effectiveSort(query);
        return jdbc.sql(sql.toString())
                .params(filter.parameters())
                .query((rs, row) -> new SearchRow(mapCard(rs, row), sortValue(rs, sort)))
                .list();
    }

    private String sortValue(ResultSet rs, BookSort sort) throws SQLException {
        return switch (sort) {
            case TITLE -> rs.getString("title") == null ? "" : rs.getString("title");
            case RELEVANCE -> String.valueOf(rs.getFloat("rank"));
            default -> rs.getTimestamp("created_at").toInstant().toString();
        };
    }

    /** Перечни значений считаются по тому же отбору, что и сама выдача. */
    public Facets facets(BookQuery query, String ownerId) {
        return new Facets(
                countBy(query, ownerId, "b.format", null),
                countBy(
                        query,
                        ownerId,
                        "ft.name",
                        " JOIN book_theme fbt ON fbt.book_id = b.id"
                                + " JOIN theme ft ON ft.id = fbt.theme_id"),
                countBy(query, ownerId, "b.language", null),
                countBy(query, ownerId, "b.shelf", null));
    }

    public void updateShelf(UUID id, String ownerId, Shelf shelf) {
        jdbc.sql(
                        """
                        UPDATE book SET shelf = :shelf, updated_at = now()
                        WHERE id = :id AND owner_id = :ownerId
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("shelf", shelf.name())
                .update();
    }

    public void updateFavorite(UUID id, String ownerId, boolean favorite) {
        jdbc.sql(
                        """
                        UPDATE book SET favorite = :favorite, updated_at = now()
                        WHERE id = :id AND owner_id = :ownerId
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("favorite", favorite)
                .update();
    }

    public boolean delete(UUID id, String ownerId) {
        return jdbc.sql("DELETE FROM book WHERE id = :id AND owner_id = :ownerId")
                        .param("id", id)
                        .param("ownerId", ownerId)
                        .update()
                > 0;
    }

    /** Все файлы владельца: нужны выгрузке всей библиотеки. */
    public List<String> findAllFiles(String ownerId) {
        return jdbc.sql("SELECT sha256 FROM book WHERE owner_id = :ownerId ORDER BY created_at")
                .param("ownerId", ownerId)
                .query(String.class)
                .list();
    }

    private List<Facets.FacetValue> countBy(
            BookQuery query, String ownerId, String column, String extraJoin) {
        Filter filter = filterFor(query, ownerId);
        String sql =
                "SELECT %s AS value, count(DISTINCT b.id) AS amount FROM book b %s %s WHERE %s"
                                .formatted(
                                        column,
                                        filter.joins(),
                                        extraJoin == null ? "" : extraJoin,
                                        filter.conditions())
                        + " GROUP BY "
                        + column
                        + " ORDER BY amount DESC, value";
        return jdbc
                .sql(sql)
                .params(filter.parameters())
                .query(
                        (rs, row) -> {
                            String value = rs.getString("value");
                            return value == null
                                    ? null
                                    : new Facets.FacetValue(value, rs.getLong("amount"));
                        })
                .list()
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Условия отбора и значения к ним.
     *
     * @param conditions часть WHERE
     * @param joins присоединяемые таблицы
     * @param parameters значения
     */
    private record Filter(String conditions, String joins, Map<String, Object> parameters) {}

    private Filter filterFor(BookQuery query, String ownerId) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        final StringBuilder joins = new StringBuilder();

        conditions.add("b.owner_id = :ownerId");
        parameters.put("ownerId", ownerId);

        if (query.hasText()) {
            // Три способа найти одно и то же, и каждый берёт своё. Разбор на слова —
            // основной: он знает про окончания и порядок. Поиск подстроки нужен, когда
            // человек набрал половину слова. Похожесть по триграммам ловит опечатки,
            // и меряется она по слову, а не по всей строке: у длинного названия
            // похожесть на короткий запрос всегда мала, сколько бы букв ни совпало.
            conditions.add(
                    "(b.search @@ websearch_to_tsquery('"
                            + TEXT_SEARCH_CONFIG
                            + "', :text)"
                            + " OR b.title ILIKE :textLike"
                            + " OR word_similarity(:text, coalesce(b.title, '')) > "
                            + TYPO_THRESHOLD
                            + ")");
            parameters.put("text", query.text());
            parameters.put("textLike", "%" + query.text() + "%");
        }
        if (!query.formats().isEmpty()) {
            conditions.add("b.format IN (:formats)");
            parameters.put("formats", query.formats().stream().map(BookFormat::name).toList());
        }
        if (!query.languages().isEmpty()) {
            conditions.add("b.language IN (:languages)");
            parameters.put("languages", query.languages());
        }
        if (query.yearFrom() != null) {
            conditions.add("b.year >= :yearFrom");
            parameters.put("yearFrom", query.yearFrom());
        }
        if (query.yearTo() != null) {
            conditions.add("b.year <= :yearTo");
            parameters.put("yearTo", query.yearTo());
        }
        if (query.shelf() != null) {
            conditions.add("b.shelf = :shelf");
            parameters.put("shelf", query.shelf().name());
        }
        if (query.favorite() != null) {
            conditions.add("b.favorite = :favorite");
            parameters.put("favorite", query.favorite());
        }
        if (!query.themes().isEmpty()) {
            joins.append(" JOIN book_theme bt ON bt.book_id = b.id")
                    .append(" JOIN theme t ON t.id = bt.theme_id AND t.name IN (:themes)");
            parameters.put("themes", query.themes());
        }
        if (query.collectionId() != null) {
            joins.append(
                    " JOIN collection_book cb ON cb.book_id = b.id"
                            + " AND cb.collection_id = :collectionId");
            parameters.put("collectionId", query.collectionId());
        }
        return new Filter(String.join(" AND ", conditions), joins.toString(), parameters);
    }

    private String rankColumn(BookQuery query) {
        if (query.sort() != BookSort.RELEVANCE || !query.hasText()) {
            return "";
        }
        return ", ts_rank(b.search, websearch_to_tsquery('"
                + TEXT_SEARCH_CONFIG
                + "', :text)) AS rank";
    }

    /**
     * Место, с которого продолжать, задаётся значением последней показанной книги, а не номером
     * страницы. Сравнение идёт по паре «поле порядка и идентификатор»: одних дат мало, они
     * повторяются, и книга на границе страниц либо пропадёт, либо покажется дважды.
     */
    private void appendCursorCondition(
            StringBuilder sql, BookQuery query, Cursor cursor, Map<String, Object> parameters) {
        if (cursor == null) {
            return;
        }
        parameters.put("cursorId", cursor.id());
        switch (effectiveSort(query)) {
            case TITLE -> {
                sql.append(" AND (coalesce(b.title, ''), b.id) > (:cursorValue, :cursorId)");
                parameters.put("cursorValue", cursor.value());
            }
            case RELEVANCE -> {
                sql.append(" AND (ts_rank(b.search, websearch_to_tsquery('")
                        .append(TEXT_SEARCH_CONFIG)
                        .append("', :text)), b.id) < (:cursorRank, :cursorId)");
                parameters.put("cursorRank", Float.parseFloat(cursor.value()));
            }
            default -> {
                sql.append(" AND (b.created_at, b.id) < (:cursorValue, :cursorId)");
                parameters.put(
                        "cursorValue", java.sql.Timestamp.from(Instant.parse(cursor.value())));
            }
        }
    }

    private String orderBy(BookQuery query) {
        return switch (effectiveSort(query)) {
            case TITLE -> " ORDER BY coalesce(b.title, '') ASC, b.id ASC";
            case RELEVANCE -> " ORDER BY rank DESC, b.id DESC";
            default -> " ORDER BY b.created_at DESC, b.id DESC";
        };
    }

    /** Сколько карточек в каком состоянии: по этому считается размер библиотеки. */
    public java.util.Map<BookStatus, Integer> countByStatus() {
        return jdbc
                .sql("SELECT status, count(*) AS amount FROM book GROUP BY status")
                .query(
                        (rs, rowNum) ->
                                java.util.Map.entry(
                                        BookStatus.valueOf(rs.getString("status")),
                                        rs.getInt("amount")))
                .list()
                .stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

    /** Порядок по совпадению без строки поиска не имеет смысла — тогда это порядок добавления. */
    private BookSort effectiveSort(BookQuery query) {
        return query.sort() == BookSort.RELEVANCE && !query.hasText()
                ? BookSort.ADDED
                : query.sort();
    }

    private BookCard mapCard(ResultSet rs, int rowNumber) throws SQLException {
        return new BookCard(
                rs.getObject("id", UUID.class),
                rs.getString("sha256"),
                rs.getString("original_name"),
                BookFormat.valueOf(rs.getString("format")),
                rs.getString("title"),
                List.of(),
                (Integer) rs.getObject("year"),
                rs.getString("language"),
                rs.getString("isbn"),
                rs.getString("series"),
                (Integer) rs.getObject("series_number"),
                rs.getString("publisher"),
                List.of(),
                rs.getString("cover_sha256"),
                BookStatus.valueOf(rs.getString("status")),
                Shelf.valueOf(rs.getString("shelf")),
                rs.getBoolean("favorite"),
                readSources(rs.getString("sources")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String writeSources(Map<String, MetadataSource> sources) {
        try {
            return json.writeValueAsString(sources);
        } catch (JacksonException exception) {
            throw new IllegalStateException("не удалось записать источники полей", exception);
        }
    }

    private Map<String, MetadataSource> readSources(String sources) {
        if (sources == null || sources.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> raw = json.readValue(sources, Map.class);
            Map<String, MetadataSource> result = new LinkedHashMap<>();
            raw.forEach((field, source) -> result.put(field, MetadataSource.valueOf(source)));
            return result;
        } catch (JacksonException exception) {
            throw new IllegalStateException("не удалось прочитать источники полей", exception);
        }
    }

    /**
     * Место, с которого продолжать выдачу.
     *
     * @param value значение поля, по которому идёт порядок
     * @param id идентификатор последней показанной книги
     */
    public record Cursor(String value, UUID id) {}

    /**
     * Строка выдачи.
     *
     * @param card карточка
     * @param sortValue значение поля порядка — из него собирается место продолжения
     */
    public record SearchRow(BookCard card, String sortValue) {}
}
