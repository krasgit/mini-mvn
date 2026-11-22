package mini.maven.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class Xml {

    private Xml() {
    }

    public static XmlNode parse(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file);
        } catch (IOException e) {
            throw new XmlException(file, "cannot read XML: " + e.getMessage());
        }
    }

    public static XmlNode parse(String xml, Path source) {
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parse(in, source);
        } catch (IOException e) {
            throw new XmlException(source, "cannot read XML: " + e.getMessage());
        }
    }

    private static XmlNode parse(InputStream in, Path source) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        Deque<XmlNode> stack = new ArrayDeque<>();
        XmlNode root = null;

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    switch (event) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            Map<String, String> attrs = new LinkedHashMap<>();
                            for (int i = 0; i < reader.getAttributeCount(); i++) {
                                attrs.put(reader.getAttributeLocalName(i),
                                        reader.getAttributeValue(i));
                            }
                            XmlNode node = new XmlNode(source, reader.getLocalName(),
                                    reader.getNamespaceURI(), attrs,
                                    reader.getLocation().getLineNumber(),
                                    reader.getLocation().getColumnNumber());
                            if (stack.isEmpty()) {
                                if (root != null) {
                                    throw new XmlException(source, node.line(), node.column(),
                                            "more than one root element");
                                }
                                root = node;
                            } else {
                                stack.peek().add(node);
                            }
                            stack.push(node);
                        }
                        case XMLStreamConstants.END_ELEMENT -> {
                            if (stack.isEmpty()) {
                                throw new XmlException(source, "unbalanced end element");
                            }
                            stack.pop();
                        }
                        case XMLStreamConstants.CHARACTERS,
                             XMLStreamConstants.CDATA,
                             XMLStreamConstants.SPACE -> {
                            if (!stack.isEmpty()) stack.peek().appendText(reader.getText());
                        }
                        case XMLStreamConstants.DTD -> throw new XmlException(source,
                                "DOCTYPE is not allowed");
                        default -> {
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new XmlException(source, "cannot parse XML: " + e.getMessage());
        }

        if (root == null) throw new XmlException(source, "empty document");
        if (!stack.isEmpty()) throw new XmlException(source, "unclosed element <"
                + stack.peek().name() + ">");
        return root;
    }

    public static XmlNode root(Path file, String name, String namespace) {
        return root(parse(file), file, name, namespace);
    }

    public static XmlNode root(String xml, Path source, String name, String namespace) {
        return root(parse(xml, source), source, name, namespace);
    }

    private static XmlNode root(XmlNode root, Path source, String name, String namespace) {
        if (!root.name().equals(name)) {
            throw new XmlException(source, root.line(), root.column(),
                    "expected <" + name + ">, got <" + root.name() + ">");
        }
        root.expectNamespace(namespace);
        return root;
    }
}
