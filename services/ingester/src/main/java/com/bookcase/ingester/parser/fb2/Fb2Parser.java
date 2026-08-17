package com.bookcase.ingester.parser.fb2;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.RawMetadata;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * Разбор FB2.
 *
 * <p>FB2 — это XML целиком, вместе с текстом книги, и описание лежит в самом начале, в элементе
 * description. Поэтому читается файл потоком и ровно до конца описания: строить в памяти дерево
 * всего документа значило бы держать книгу целиком, да ещё и в разы более объёмном виде — на файле
 * в сотни мегабайт это кончится нехваткой памяти, а прочитано всё равно будет несколько килобайт из
 * начала.
 *
 * <p>Имя автора здесь разложено по частям — фамилия, имя, отчество отдельными элементами, — и это
 * единственный формат, где не приходится гадать, что из написанного фамилия.
 *
 * <p>Файл часто встречается упакованным в zip: тогда книга внутри одна, её и читаем.
 *
 * <p>Объявления типа документа и внешние сущности при разборе выключены: иначе чтение присланного
 * файла становится способом заставить сервис сходить в сеть или прочитать файл с диска.
 */
@Component
public class Fb2Parser implements BookParser {

    private static final String DESCRIPTION = "description";
    private static final String TITLE_INFO = "title-info";
    private static final String PUBLISH_INFO = "publish-info";

    @Override
    public BookFormat format() {
        return BookFormat.FB2;
    }

    @Override
    public RawMetadata parse(Path file) {
        try (InputStream content = open(file)) {
            return readDescription(content);
        } catch (IOException exception) {
            throw new UnsupportedContentException("файл FB2 не читается", exception);
        } catch (XMLStreamException exception) {
            throw new UnsupportedContentException("файл FB2 не разбирается", exception);
        }
    }

    private RawMetadata readDescription(InputStream content) throws XMLStreamException {
        // Ограничения задаются здесь же, а не в отдельном методе: анализатор связывает
        // настройку и создание разборщика, только когда они рядом.
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        XMLStreamReader reader = factory.createXMLStreamReader(content);
        Description description = new Description();
        String section = null;
        Author author = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName();
                switch (name) {
                    case TITLE_INFO, PUBLISH_INFO -> section = name;
                    case "author" -> author = TITLE_INFO.equals(section) ? new Author() : null;
                    case "sequence" -> {
                        if (TITLE_INFO.equals(section)) {
                            description.series = attribute(reader, "name");
                            description.seriesNumber = attribute(reader, "number");
                        }
                    }
                    default -> readValue(reader, name, section, author, description);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName();
                if ("author".equals(name) && author != null) {
                    description.addAuthor(author);
                    author = null;
                } else if (DESCRIPTION.equals(name)) {
                    // Дальше идёт текст книги, а он здесь не нужен.
                    break;
                }
            }
        }
        return description.toMetadata();
    }

    private void readValue(
            XMLStreamReader reader,
            String name,
            String section,
            Author author,
            Description description)
            throws XMLStreamException {
        if (author != null) {
            switch (name) {
                case "first-name" -> author.firstName = text(reader);
                case "middle-name" -> author.middleName = text(reader);
                case "last-name" -> author.lastName = text(reader);
                case "nickname" -> author.nickname = text(reader);
                default -> {
                    // Прочие сведения об авторе разбору не нужны.
                }
            }
            return;
        }
        if (TITLE_INFO.equals(section)) {
            switch (name) {
                case "book-title" -> description.title = text(reader);
                case "date" -> description.date = text(reader);
                case "lang" -> description.language = text(reader);
                default -> {
                    // Обложка, жанры и аннотация здесь не читаются.
                }
            }
        } else if (PUBLISH_INFO.equals(section)) {
            switch (name) {
                case "publisher" -> description.publisher = text(reader);
                case "isbn" -> description.isbn = text(reader);
                default -> {
                    // Год и город издания в карточку не идут.
                }
            }
        }
    }

    private String text(XMLStreamReader reader) throws XMLStreamException {
        String value = reader.getElementText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    // Правила «закрывайте через try-с-ресурсами» здесь неприменимы: архив живёт дольше метода —
    // его владельцем становится возвращённый поток, и закрывается он вместе с ним. Во всех
    // остальных исходах архив закрывает finally ниже.
    @SuppressWarnings({"java:S2095", "java:S2093"})
    private InputStream open(Path file) throws IOException {
        if (!isZip(file)) {
            return new BufferedInputStream(Files.newInputStream(file));
        }
        ZipFile zip = new ZipFile(file.toFile());
        // Архив закрывается здесь во всех случаях, кроме одного: когда наружу ушёл поток
        // с содержимым — тогда его закрывает сам поток. Без этого разбора любой отказ
        // между открытием архива и возвратом потока оставлял бы файл открытым.
        boolean handedOver = false;
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) {
                    InputStream content = zip.getInputStream(entry);
                    handedOver = true;
                    return new java.io.FilterInputStream(content) {
                        @Override
                        public void close() throws IOException {
                            super.close();
                            zip.close();
                        }
                    };
                }
            }
            throw new UnsupportedContentException("в архиве нет файла FB2");
        } finally {
            if (!handedOver) {
                zip.close();
            }
        }
    }

    private boolean isZip(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(4);
            return header.length == 4 && header[0] == 0x50 && header[1] == 0x4b;
        }
    }

    /** Собираемое описание книги. */
    private static final class Description {
        private String title;
        private String date;
        private String language;
        private String isbn;
        private String series;
        private String seriesNumber;
        private String publisher;
        private final List<String> authors = new ArrayList<>();

        private void addAuthor(Author author) {
            String name = author.fullName();
            if (!name.isEmpty()) {
                authors.add(name);
            }
        }

        private RawMetadata toMetadata() {
            return new RawMetadata(
                    title, authors, date, language, isbn, series, seriesNumber, publisher);
        }
    }

    /** Имя автора по частям, как его пишет формат. */
    private static final class Author {
        private String firstName;
        private String middleName;
        private String lastName;
        private String nickname;

        /** Части собираются в естественном порядке; переставит их уже нормализация. */
        private String fullName() {
            String name =
                    String.join(
                                    " ",
                                    nullToEmpty(firstName),
                                    nullToEmpty(middleName),
                                    nullToEmpty(lastName))
                            .replaceAll("\\s+", " ")
                            .trim();
            return name.isEmpty() ? nullToEmpty(nickname).trim() : name;
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
