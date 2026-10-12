package hotel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Minimal JSON reader/writer so the backend needs no library beyond the JDBC driver.
final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    // =========================
    // PARSE
    // =========================

    static Object parse(String text) {
        Json reader = new Json(text);
        reader.skipSpace();
        Object value = reader.readValue();
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw reader.fail("Unexpected content after JSON value");
        }
        return value;
    }

    private Object readValue() {
        if (pos >= text.length()) {
            throw fail("Unexpected end of JSON");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': return readLiteral("true", Boolean.TRUE);
            case 'f': return readLiteral("false", Boolean.FALSE);
            case 'n': return readLiteral("null", null);
            default:  return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipSpace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw fail("Expected a property name");
            }
            String key = readString();
            skipSpace();
            expect(':');
            skipSpace();
            map.put(key, readValue());
            skipSpace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw fail("Expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipSpace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipSpace();
            list.add(readValue());
            skipSpace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw fail("Expected ',' or ']'");
            }
        }
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"': case '\\': case '/': sb.append(e); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw fail("Incomplete unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw fail("Invalid unicode escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw fail("Invalid escape sequence");
            }
        }
    }

    private Object readLiteral(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw fail("Unexpected token");
        }
        pos += word.length();
        return value;
    }

    private BigDecimal readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos || pos - start > 40) {
            throw fail("Unexpected token");
        }
        try {
            return new BigDecimal(text.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw fail("Invalid number");
        }
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw fail("Unexpected end of JSON");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw fail("Expected '" + c + "'");
        }
    }

    private IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }

    // =========================
    // WRITE
    // =========================

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof BigDecimal number) {
            sb.append(number.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            quote(sb, value.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    // < and > are escaped so a response can never close a script tag
                    if (c < 0x20 || c == '<' || c == '>' || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
