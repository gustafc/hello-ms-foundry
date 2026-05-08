package se.mejsla.chatbot;

import picocli.CommandLine;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ChatbotCommand()).execute(args));
    }
}
