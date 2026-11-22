package education.devtools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {}

    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return '"' + escape(text) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(write(key.toString()) + ":" + write(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> encoded = new ArrayList<>();
            items.forEach(item -> encoded.add(write(item)));
            return "[" + String.join(",", encoded) + "]";
        }
        throw new IllegalArgumentException("Cannot encode " + value.getClass());
    }

    static Object read(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.space();
        if (!parser.done()) throw new IllegalArgumentException("Unexpected JSON suffix");
        return value;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) { this.text = text; }
        private boolean done() { return index == text.length(); }
        private void space() { while (!done() && Character.isWhitespace(text.charAt(index))) index++; }

        private Object value() {
            space();
            if (done()) throw new IllegalArgumentException("Expected JSON value");
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                String key = string();
                space();
                expect(':');
                result.put(key, value());
                space();
            } while (take(','));
            expect('}');
            return result;
        }

        private List<Object> array() {
            index++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do { result.add(value()); space(); } while (take(','));
            expect(']');
            return result;
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!done()) {
                char current = text.charAt(index++);
                if (current == '"') return result.toString();
                if (current != '\\') { result.append(current); continue; }
                if (done()) throw new IllegalArgumentException("Incomplete JSON escape");
                char escaped = text.charAt(index++);
                result.append(switch (escaped) {
                    case '"', '\\', '/' -> escaped;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                    case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                });
            }
            throw new IllegalArgumentException("Unclosed JSON string");
        }

        private char unicode() {
            if (index + 4 > text.length()) throw new IllegalArgumentException("Incomplete unicode escape");
            char value = (char) Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return value;
        }

        private Object number() {
            int start = index;
            while (!done() && "-+0123456789.eE".indexOf(text.charAt(index)) >= 0) index++;
            String token = text.substring(start, index);
            if (token.isEmpty()) throw new IllegalArgumentException("Invalid JSON value");
            return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, index)) throw new IllegalArgumentException("Invalid JSON literal");
            index += token.length();
            return value;
        }

        private boolean take(char expected) {
            if (!done() && text.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Expected " + expected);
        }
    }
}
