package com.bookcase.ingester.parser;

import com.bookcase.ingester.exception.UnsupportedContentException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Разбор XML из файлов, пришедших от кого угодно.
 *
 * <p>Объявления типа документа выключены полностью. Иначе разбор чужого файла становится способом
 * заставить сервис прочитать файл с диска или сходить в сеть за «сущностью» — приём известный и
 * работающий на любом разборщике, где это не запретили.
 */
public final class SafeXml {

    private SafeXml() {}

    @SuppressFBWarnings(
            value = "XXE_DOCUMENT",
            justification =
                    "Разборщик создаётся методом builder() ниже: там выключены объявления "
                            + "типа документа, внешние сущности и загрузка внешних описаний схем. "
                            + "Анализатор не связывает две части, потому что они в разных методах.")
    public static Document parse(InputStream input, String whatIsBeingRead) {
        try {
            return builder().parse(input);
        } catch (SAXException | IOException exception) {
            throw new UnsupportedContentException(
                    whatIsBeingRead + ": не удалось разобрать", exception);
        }
    }

    private static DocumentBuilder builder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException(
                    "разборщик XML не поддерживает обязательные ограничения", exception);
        }
    }
}
