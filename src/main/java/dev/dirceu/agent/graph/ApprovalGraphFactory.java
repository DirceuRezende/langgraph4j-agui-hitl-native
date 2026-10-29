package dev.dirceu.agent.graph;

import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.message.Role;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.agui.sdk.AGUINodeOutput;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.utils.EdgeMappings;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

public final class ApprovalGraphFactory {
    private ApprovalGraphFactory() {}

    /**
     * START -> draft -> review [HITL interrupt] -> execute | reject -> END.
     * No LLM routing: the human's boolean decision selects the edge.
     */
    public static CompiledGraph<ApprovalState> build(
            DraftGenerator generator,
            BaseCheckpointSaver saver) throws GraphStateException {
        return new StateGraph<>(ApprovalState::new)
                .addNode("draft", node_async((state, config) -> {
                    // Each token is emitted directly to LangGraph4j's own stream.
                    // AGUIAgentBase automatically forwards these AGUINodeOutput events.
                    var dispatcher = config.<ApprovalState, AGUINodeOutput<ApprovalState>>customDispatcher();
                    var messageId = state.threadId() + "-draft";
                    dispatcher.dispatchAsync(AGUINodeOutput.<ApprovalState>builder()
                            .addEvent(new TextMessageStartEvent(messageId, Role.ASSISTANT))
                            .build("draft", state));
                    try {
                        var draft = generator.generate(state.prompt(), token -> {
                            if (token != null && !token.isEmpty()) {
                                dispatcher.dispatchAsync(AGUINodeOutput.<ApprovalState>builder()
                                        .addEvent(new TextMessageContentEvent(messageId, token))
                                        .build("draft", state));
                            }
                        });
                        return Map.of("draft", draft);
                    } finally {
                        dispatcher.dispatchAsync(AGUINodeOutput.<ApprovalState>builder()
                                .addEvent(new TextMessageEndEvent(messageId))
                                .build("draft", state));
                    }
                }))
                .addNode("review", node_async((state, config) -> Map.of()))
                .addNode("execute", node_async((state, config) -> Map.of(
                        "finalMessage", "Aprovado. Ação de demonstração concluída: " + state.draft())))
                .addNode("reject", node_async((state, config) -> Map.of(
                        "finalMessage", "Rascunho rejeitado. Nenhuma ação foi executada.")))
                .addEdge(START, "draft")
                .addEdge("draft", "review")
                .addConditionalEdges("review",
                        edge_async(state -> state.approved() ? "execute" : "reject"),
                        EdgeMappings.builder().to("execute").to("reject").build())
                .addEdge("execute", END)
                .addEdge("reject", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .interruptBefore("review")
                        // Keep completed threads queryable for refresh/debugging.
                        .releaseThread(false)
                        .build());
    }
}
