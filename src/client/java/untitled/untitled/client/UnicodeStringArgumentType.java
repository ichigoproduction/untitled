package untitled.untitled.client;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.Collection;
import java.util.List;

/**
 * A quotable single-token string argument that also accepts non-ASCII characters
 * such as Korean, Japanese, and Chinese without requiring quotes.
 */
public final class UnicodeStringArgumentType implements ArgumentType<String> {
    private static final UnicodeStringArgumentType INSTANCE = new UnicodeStringArgumentType();
    private static final Collection<String> EXAMPLES = List.of(
            "청람멸도",
            "日本刀",
            "\"스카이 콩콩\""
    );

    private UnicodeStringArgumentType() {
    }

    public static UnicodeStringArgumentType string() {
        return INSTANCE;
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead()) {
            return "";
        }

        char first = reader.peek();
        if (StringReader.isQuotedStringStart(first)) {
            reader.skip();
            return reader.readStringUntil(first);
        }

        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
