package se.mejsla.chatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class ReadFileTool implements Tool {

    public static final long DEFAULT_MAX_BYTES = 256L * 1024L;

    private final Path root;
    private final long maxBytes;

    public ReadFileTool(Path root) {
        this(root, DEFAULT_MAX_BYTES);
    }

    public ReadFileTool(Path root, long maxBytes) {
        try {
            this.root = root.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "tool root does not exist or is unreadable: " + root, e);
        }
        this.maxBytes = maxBytes;
    }

    public Path root() {
        return root;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a UTF-8 text file from the user's machine and return its contents. "
                + "Use this when the user references a file or when reading the file would let "
                + "you give a more accurate answer. Do not invent paths the user has not "
                + "mentioned; if you are unsure of the path, ask first.";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Path to the file. Relative paths resolve from the chatbot's working directory. Must live inside the chatbot's sandbox root."
                    }
                  },
                  "required": ["path"],
                  "additionalProperties": false
                }
                """;
    }

    @Override
    public String execute(JsonNode args) {
        JsonNode pathNode = args.get("path");
        if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            return "error: 'path' argument is required and must be a non-empty string";
        }
        String requested = pathNode.asText();

        Path resolved;
        try {
            resolved = root.resolve(requested).normalize();
        } catch (RuntimeException e) {
            return "error: invalid path: " + e.getMessage();
        }

        Path real;
        try {
            real = resolved.toRealPath();
        } catch (NoSuchFileException e) {
            return "error: not found: " + requested;
        } catch (IOException e) {
            return "error: unreadable: " + e.getMessage();
        }

        if (!real.startsWith(root)) {
            return "error: outside sandbox: " + requested;
        }
        if (!Files.isRegularFile(real)) {
            return "error: not a regular file: " + requested;
        }

        long size;
        try {
            size = Files.size(real);
        } catch (IOException e) {
            return "error: unreadable: " + e.getMessage();
        }
        if (size > maxBytes) {
            return "error: too large (limit " + maxBytes + " bytes, got " + size + ")";
        }

        String content;
        try {
            content = Files.readString(real, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return "error: not valid UTF-8: " + requested;
        } catch (IOException e) {
            return "error: unreadable: " + e.getMessage();
        }

        String displayPath = root.relativize(real).toString();
        if (displayPath.isEmpty()) {
            displayPath = real.getFileName().toString();
        }
        return "--- read_file path=" + displayPath + " bytes=" + size + " ---\n" + content;
    }
}
