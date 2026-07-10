package build.jenesis.repository.importer.maven;

import module java.base;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The two Maven documents the metadata refresh reads, parsed with the JDK's DOM - hardened against document type
 * declarations and external entities, since the documents come from the (semi-trusted) migration source and an
 * entity expansion must not become a fetch. Both readers navigate direct children only, so a {@code packaging}
 * buried in a plugin configuration or a {@code version} outside the versions block is never mistaken for the value.
 */
final class MavenXml {

    private MavenXml() {
    }

    /** The versions a {@code maven-metadata.xml} lists ({@code metadata > versioning > versions > version}), empty
     *  when the document does not parse - a broken metadata skips the refresh, it does not fail the walk. */
    static List<String> versions(byte[] metadata) {
        Element root = parse(metadata);
        Element versions = child(child(root, "versioning"), "versions");
        if (versions == null) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (Node node = versions.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals("version")) {
                String version = element.getTextContent().trim();
                if (!version.isEmpty()) {
                    parsed.add(version);
                }
            }
        }
        return parsed;
    }

    /** A pom's project-level packaging, {@code jar} when it declares none, or {@code null} when the document does
     *  not parse (the caller then imports the pom as it is but derives no primary artifact from it). */
    static String packaging(byte[] pom) {
        Element root = parse(pom);
        if (root == null) {
            return null;
        }
        Element packaging = child(root, "packaging");
        if (packaging == null) {
            return "jar";
        }
        String value = packaging.getTextContent().trim();
        return value.isEmpty() ? "jar" : value;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static Element parse(byte[] document) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException unparseable) {
            return null;
        }
    }
}
