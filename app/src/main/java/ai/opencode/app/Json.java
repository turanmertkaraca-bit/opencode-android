package ai.opencode.app;

import android.util.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency JSON helpers on top of android.util.JsonReader.
 * Everything decodes to plain Java types: Map / List / String / Double /
 * Boolean / null — enough for every opencode server payload we consume.
 */
public final class Json {

    private Json() {}

    /** Parse a JSON document into plain Java objects; returns null on malformed input. */
    public static Object parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            JsonReader r = new JsonReader(new StringReader(s));
            r.setLenient(true);
            Object v = readValue(r);
            r.close();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static Map<String, Object> map(Map<String, Object> m, String key) {
        return obj(m == null ? null : m.get(key));
    }

    public static List<Object> list(Map<String, Object> m, String key) {
        return arr(m == null ? null : m.get(key));
    }

    /** Deep-search for the first "message"/"msg"/"error" style text in an arbitrary payload. */
    public static String findErrorText(Object o, int depth) {
        if (o == null || depth > 6) return null;
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            for (String k : new String[]{"message", "msg", "error", "detail"}) {
                Object v = m.get(k);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            }
            for (Object v : m.values()) {
                String s = findErrorText(v, depth + 1);
                if (s != null) return s;
            }
        } else if (o instanceof List) {
            for (Object v : (List<?>) o) {
                String s = findErrorText(v, depth + 1);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static Object readValue(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_OBJECT: {
                Map<String, Object> m = new LinkedHashMap<>();
                r.beginObject();
                while (r.hasNext()) m.put(r.nextName(), readValue(r));
                r.endObject();
                return m;
            }
            case BEGIN_ARRAY: {
                List<Object> l = new ArrayList<>();
                r.beginArray();
                while (r.hasNext()) l.add(readValue(r));
                r.endArray();
                return l;
            }
            case STRING:  return r.nextString();
            case BOOLEAN: return r.nextBoolean();
            case NULL:    r.nextNull(); return null;
            case NUMBER:  return r.nextDouble();
            default:      r.skipValue(); return null;
        }
    }

    /** Minimal JSON string escaper for request bodies we build by hand. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }
}
