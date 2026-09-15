package untitled.untitled.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ModelCommandParser {
    record ModelMapping(String itemName, String itemId) {
    }

    record CopyMapping(String sourceName, String targetName) {
    }

    record CopyStateMapping(String sourceName, String targetName, String state) {
    }

    private ModelCommandParser() {
    }

    static ModelMapping parseModelMapping(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        int split = trimmed.lastIndexOf(' ');
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }

        String itemName = normalizeNameArgument(trimmed.substring(0, split));
        String itemId = trimmed.substring(split + 1).trim();
        if (itemName == null || itemName.isBlank() || itemId.isEmpty()) {
            return null;
        }
        return new ModelMapping(itemName, itemId);
    }

    static CopyMapping parseCopyMapping(String value) {
        CopyStateMapping mapping = parseCopyStateMapping(value);
        if (mapping == null || mapping.state() != null) {
            return null;
        }
        return new CopyMapping(mapping.sourceName(), mapping.targetName());
    }

    static CopyStateMapping parseCopyStateMapping(String value) {
        List<String> parts = tokenize(value);
        if (parts.size() != 2 && parts.size() != 3) {
            return null;
        }

        String source = normalizeNameArgument(parts.get(0));
        String target = normalizeNameArgument(parts.get(1));
        if (source == null || target == null || source.isBlank() || target.isBlank()) {
            return null;
        }

        String state = null;
        if (parts.size() == 3) {
            state = parts.get(2).toLowerCase(Locale.ROOT);
            if (!state.equals("sheathed") && !state.equals("drawn")) {
                return null;
            }
        }
        return new CopyStateMapping(source, target, state);
    }

    static String normalizeNameArgument(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && trimmed.startsWith("\"")
                && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static List<String> tokenize(String value) {
        if (value == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaping = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);

            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }

            if (quoted && ch == '\\') {
                escaping = true;
                continue;
            }

            if (ch == '"') {
                quoted = !quoted;
                continue;
            }

            if (Character.isWhitespace(ch) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (escaping || quoted) {
            return List.of();
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }
}
