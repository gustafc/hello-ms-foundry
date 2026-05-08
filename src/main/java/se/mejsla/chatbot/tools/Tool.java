package se.mejsla.chatbot.tools;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {

    String name();

    String description();

    String parametersJsonSchema();

    String execute(JsonNode args);
}
