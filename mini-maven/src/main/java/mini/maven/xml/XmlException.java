package mini.maven.xml;

import java.nio.file.Path;

public final class XmlException extends RuntimeException {

    private final Path file;
    private final int line;
    private final int column;

    public XmlException(Path file, int line, int column, String message) {
        super(message);
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public XmlException(Path file, String message) {
        this(file, 0, 0, message);
    }

    public Path file() {
        return file;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    @Override
    public String getMessage() {
        String where = file == null ? "" : file.toString();
        if (line > 0) where += ":" + line + (column > 0 ? ":" + column : "");
        return where.isEmpty() ? super.getMessage() : where + ": " + super.getMessage();
    }
}
