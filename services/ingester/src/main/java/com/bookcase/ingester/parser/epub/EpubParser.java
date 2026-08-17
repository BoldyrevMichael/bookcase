package com.bookcase.ingester.parser.epub;

import com.bookcase.events.BookFormat;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.parser.BookParser;
import com.bookcase.ingester.parser.RawMetadata;
import com.bookcase.ingester.parser.SafeXml;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Разбор EPUB.
 *
 * <p>EPUB — это zip, внутри которого файл META-INF/container.xml указывает на описание книги. Само
 * описание — XML со словарём Dublin Core: название, авторы, дата, язык, издательство,
 * идентификаторы. Серия в стандарт не входит, но её почти всегда пишет Calibre — отдельным
 * элементом meta, и прочитать её стоит: без неё книги цикла рассыпаются.
 *
 * <p>Своих библиотек для этого не нужно: zip и XML есть в самой платформе.
 */
@Component
public class EpubParser implements BookParser {

    private static final String DUBLIN_CORE = "http://purl.org/dc/elements/1.1/";
    private static final String CONTAINER_PATH = "META-INF/container.xml";

    @Override
    public BookFormat format() {
        return BookFormat.EPUB;
    }

    @Override
    public RawMetadata parse(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Document packageDocument = readPackageDocument(zip);
            Element metadata =
                    firstElement(packageDocument.getElementsByTagNameNS("*", "metadata"));
            if (metadata == null) {
                return RawMetadata.empty();
            }
            return new RawMetadata(
                    text(metadata, "title"),
                    authors(metadata),
                    text(metadata, "date"),
                    text(metadata, "language"),
                    isbn(metadata),
                    calibreMeta(metadata, "calibre:series"),
                    calibreMeta(metadata, "calibre:series_index"),
                    text(metadata, "publisher"));
        } catch (IOException exception) {
            throw new UnsupportedContentException("файл EPUB не читается", exception);
        }
    }

    private Document readPackageDocument(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry(CONTAINER_PATH);
        if (container == null) {
            throw new UnsupportedContentException("в EPUB нет " + CONTAINER_PATH);
        }
        String packagePath;
        try (InputStream content = zip.getInputStream(container)) {
            Element rootfile =
                    firstElement(
                            SafeXml.parse(content, CONTAINER_PATH)
                                    .getElementsByTagNameNS("*", "rootfile"));
            if (rootfile == null) {
                throw new UnsupportedContentException("в EPUB не указано описание книги");
            }
            packagePath = rootfile.getAttribute("full-path");
        }
        ZipEntry packageEntry = zip.getEntry(packagePath);
        if (packageEntry == null) {
            throw new UnsupportedContentException(
                    "в EPUB нет описания книги по пути " + packagePath);
        }
        try (InputStream content = zip.getInputStream(packageEntry)) {
            return SafeXml.parse(content, "описание книги EPUB");
        }
    }

    private List<String> authors(Element metadata) {
        List<String> authors = new ArrayList<>();
        NodeList creators = metadata.getElementsByTagNameNS(DUBLIN_CORE, "creator");
        for (int i = 0; i < creators.getLength(); i++) {
            String value = creators.item(i).getTextContent();
            if (value != null && !value.isBlank()) {
                authors.add(value.trim());
            }
        }
        return authors;
    }

    /** Идентификаторов может быть несколько; нужен тот, что похож на ISBN. */
    private String isbn(Element metadata) {
        NodeList identifiers = metadata.getElementsByTagNameNS(DUBLIN_CORE, "identifier");
        for (int i = 0; i < identifiers.getLength(); i++) {
            String value = identifiers.item(i).getTextContent();
            if (value == null) {
                continue;
            }
            String digitsOnly = value.replaceAll("[^0-9Xx]", "");
            if (digitsOnly.length() == 10 || digitsOnly.length() == 13) {
                return value.trim();
            }
        }
        return null;
    }

    private String calibreMeta(Element metadata, String name) {
        NodeList metas = metadata.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if (name.equals(meta.getAttribute("name"))) {
                String content = meta.getAttribute("content");
                return content.isBlank() ? null : content.trim();
            }
        }
        return null;
    }

    private String text(Element metadata, String tag) {
        NodeList nodes = metadata.getElementsByTagNameNS(DUBLIN_CORE, tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Element firstElement(NodeList nodes) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) node;
            }
        }
        return null;
    }
}
