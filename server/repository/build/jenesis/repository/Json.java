package build.jenesis.repository;

import module java.base;

/**
 * A minimal recursive-descent JSON reader, just enough to walk the listing responses of a foreign repository
 * manager (Nexus components, Artifactory storage) during an import. The free core deliberately carries no JSON
 * library, so the source connectors parse with this; it returns the conventional tree of {@link Map},
 * {@link List}, {@link String}, {@link Double}, {@link Boolean} and {@code null}, and the typed accessors
 * ({@link #object}, {@link #array}, {@link #string}) navigate it without unchecked casts at the call site.
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    static int integer(Object value) {
        return value instanceof Double number ? number.intValue() : 0;
    }

    private Object readValue() {
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        index++;
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            index++;
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == '}') {
                return object;
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        index++;
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == ']') {
                return array;
            }
        }
    }

    private String readString() {
        StringBuilder value = new StringBuilder();
        index++;
        while (true) {
            char c = text.charAt(index++);
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\') {
                char escape = text.charAt(index++);
                switch (escape) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> value.append(escape);
                }
            } else {
                value.append(c);
            }
        }
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", index)) {
            index += "true".length();
            return Boolean.TRUE;
        }
        index += "false".length();
        return Boolean.FALSE;
    }

    private Object readNull() {
        index += "null".length();
        return null;
    }

    private Double readNumber() {
        int start = index;
        while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        return Double.valueOf(text.substring(start, index));
    }

    private char peek() {
        return text.charAt(index);
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
