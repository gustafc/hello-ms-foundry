package se.mejsla.chatbot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SystemPrompt {

    private static final String RESOURCE = "/system-prompt.md";

    private SystemPrompt() {
    }

    public static String load() {
        try (InputStream in = SystemPrompt.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(in, "missing classpath resource " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("loading " + RESOURCE, e);
        }
    }
}
