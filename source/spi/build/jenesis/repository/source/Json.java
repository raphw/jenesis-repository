package build.jenesis.repository.source;

import module java.base;
import tools.jackson.databind.json.JsonMapper;

/**
 * The import path's JSON, over Jackson. It reads a foreign manager's listing responses into the conventional tree of
 * {@link Map}, {@link List}, {@link String}, {@link Number}, {@link Boolean} and {@code null} - the typed accessors
 * ({@link #object}, {@link #array}, {@link #string}, {@link #integer}) navigate it without unchecked casts at the call
 * site - and writes a job's state object back out. Jackson does the parsing and the escaping; this only adapts it to
 * the small shape the connectors and the job runner use, so no format leaks a Jackson type across the module boundary.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** Read JSON text into the conventional {@code Map}/{@code List}/scalar tree. */
    public static Object parse(String text) {
        return MAPPER.readValue(text, Object.class);
    }

    /** Write a {@code Map}/{@code List}/scalar tree back to JSON text, Jackson handling the escaping. */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    public static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
