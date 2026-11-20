package mini.maven.xml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

public final class XmlNode {

    private final Path file;
    private final String name;
    private final String namespace;
    private final Map<String, String> attributes;
    private final List<XmlNode> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final int line;
    private final int column;

    XmlNode(Path file, String name, String namespace, Map<String, String> attributes, int line, int column) {
        this.file = file;
        this.name = name;
        this.namespace = namespace;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.line = line;
        this.column = column;
    }

    void add(XmlNode child) {
        children.add(child);
    }

    void appendText(String piece) {
        text.append(piece);
    }

    public String name() {
        return name;
    }

    public String namespace() {
        return namespace;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public List<XmlNode> children() {
        return Collections.unmodifiableList(children);
    }

    public List<XmlNode> children(String wanted) {
        List<XmlNode> out = new ArrayList<>();
        for (XmlNode child : children) {
            if (child.name.equals(wanted)) out.add(child);
        }
        return out;
    }

    public XmlNode child(String wanted) {
        for (XmlNode child : children) {
            if (child.name.equals(wanted)) return child;
        }
        return null;
    }

    public XmlNode requireChild(String wanted) {
        XmlNode child = child(wanted);
        if (child == null) throw error("missing <" + wanted + ">");
        return child;
    }

    public String text() {
        return text.toString().trim();
    }

    public String attr(String name) {
        return attributes.get(name);
    }

    public String requireAttr(String name) {
        String value = attributes.get(name);
        if (value == null || value.isBlank()) throw error("missing attribute '" + name + "'");
        return value;
    }

    public boolean booleanAttr(String name, boolean fallback) {
        String value = attributes.get(name);
        if (value == null || value.isBlank()) return fallback;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw error("attribute '" + name + "' must be true or false, got '" + value + "'");
    }

    public long longAttr(String name) {
        String value = requireAttr(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw error("attribute '" + name + "' must be an integer, got '" + value + "'");
        }
    }

    public void expectNamespace(String expected) {
        if (!Objects.equals(namespace, expected)) {
            throw error("expected namespace " + expected + ", got " + namespace);
        }
    }

    public void refuseUnknownChildren(String... allowed) {
        for (XmlNode child : children) {
            boolean ok = false;
            for (String name : allowed) {
                if (child.name.equals(name)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) throw child.error("unexpected element <" + child.name + ">");
        }
    }

    public XmlException error(String message) {
        return new XmlException(file, line, column, message);
    }
}
