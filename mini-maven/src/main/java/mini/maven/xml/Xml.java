package mini.maven.xml;

import java.io.IOException;
import java.io.InputStream;
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
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);

        Deque<XmlNode> stack = new ArrayDeque<>();
        XmlNode root = null;

        try (InputStream in = Files.newInputStream(file)) {
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
                            XmlNode node = new XmlNode(file, reader.getLocalName(),
                                    reader.getNamespaceURI(), attrs,
                                    reader.getLocation().getLineNumber(),
                                    reader.getLocation().getColumnNumber());
                            if (stack.isEmpty()) {
                                if (root != null) {
                                    throw new XmlException(file, node.line(), node.column(),
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
                                throw new XmlException(file, "unbalanced end element");
                            }
                            stack.pop();
                        }
                        case XMLStreamConstants.CHARACTERS,
                             XMLStreamConstants.CDATA,
                             XMLStreamConstants.SPACE -> {
                            if (!stack.isEmpty()) stack.peek().appendText(reader.getText());
                        }
                        case XMLStreamConstants.DTD -> throw new XmlException(file,
                                "DOCTYPE is not allowed");
                        default -> {
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new XmlException(file, "cannot parse XML: " + e.getMessage());
        } catch (IOException e) {
            throw new XmlException(file, "cannot read XML: " + e.getMessage());
        }

        if (root == null) throw new XmlException(file, "empty document");
        if (!stack.isEmpty()) throw new XmlException(file, "unclosed element <"
                + stack.peek().name() + ">");
        return root;
    }

    public static XmlNode root(Path file, String name, String namespace) {
        XmlNode root = parse(file);
        if (!root.name().equals(name)) {
            throw new XmlException(file, root.line(), root.column(),
                    "expected <" + name + ">, got <" + root.name() + ">");
        }
        root.expectNamespace(namespace);
        return root;
    }
}
