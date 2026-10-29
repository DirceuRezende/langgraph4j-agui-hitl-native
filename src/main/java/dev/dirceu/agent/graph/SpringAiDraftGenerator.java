package dev.dirceu.agent.graph;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;

@Component
public class SpringAiDraftGenerator implements DraftGenerator {
    private final ChatClient client;

    public SpringAiDraftGenerator(ChatClient.Builder builder) {
        this.client = builder.build();
    }

    @Override
    public String generate(String userPrompt, Consumer<String> onToken) {
        // Streaming is performed inside this node. The Graph still owns the
        // deterministic order and the persisted state, not the LLM.
        var chunks = client.prompt()
                .system("Você cria um rascunho curto em português. Não diga que ele foi aprovado "
                        + "ou executado. O usuário revisará antes de qualquer conclusão.")
                .user(userPrompt)
                .stream()
                .content()
                .filter(Objects::nonNull)
                .doOnNext(onToken)
                .collectList()
                .block();

        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("O modelo não retornou texto");
        }
        return String.join("", chunks);
    }
}
