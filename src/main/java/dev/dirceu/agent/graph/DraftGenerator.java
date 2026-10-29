package dev.dirceu.agent.graph;

import java.util.function.Consumer;

@FunctionalInterface
public interface DraftGenerator {
    String generate(String userPrompt, Consumer<String> onToken);
}
