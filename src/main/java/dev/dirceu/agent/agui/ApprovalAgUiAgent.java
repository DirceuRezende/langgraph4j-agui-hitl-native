package dev.dirceu.agent.agui;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.*;
import com.agui.community.core.interrupt.*;
import com.agui.community.core.message.*;
import dev.dirceu.agent.graph.ApprovalState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.agui.sdk.AGUIAgentBase;
import org.bsc.langgraph4j.agui.sdk.AGUINodeOutput;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/** Only application-specific AG-UI code: inputs, snapshots, and the approval contract. */
@Component
public class ApprovalAgUiAgent extends AGUIAgentBase {
    private final CompiledGraph<ApprovalState> workflow;

    public ApprovalAgUiAgent(CompiledGraph<ApprovalState> workflow) {
        super("approval");
        this.workflow = workflow;
    }

    @Override
    protected CompiledGraph<ApprovalState> newGraph() {
        return workflow;
    }

    @Override
    protected GraphInput graphInput(RunAgentInput input) {
        var snapshot = workflow.stateOf(runnableConfig(input));

        if (!input.resume().isEmpty()) {
            if (snapshot.isEmpty() || !"review".equals(snapshot.orElseThrow().nextNodeId())) {
                throw new IllegalArgumentException("Nenhuma aprovação pendente para este threadId");
            }
            if (input.resume().size() != 1) {
                throw new IllegalArgumentException("É esperada uma única resposta de HITL");
            }
            var resume = input.resume().getFirst();
            if (!(input.threadId() + ":review").equals(resume.interruptId())
                    || resume.status() != ResumeStatus.RESOLVED
                    || !(resume.payload() instanceof Map<?, ?> payload)
                    || !(payload.get("approved") instanceof Boolean approved)) {
                throw new IllegalArgumentException("Aprovação inválida: use {approved: boolean}");
            }
            return GraphInput.resume(Map.of("approved", approved));
        }

        if (snapshot.isPresent()) {
            throw new IllegalArgumentException("Este workflow já começou. Aprove-o ou abra uma nova conversa.");
        }

        var prompt = input.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .reduce((oldMessage, newMessage) -> newMessage)
                .map(UserMessage::content)
                .filter(text -> !text.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Mensagem de usuário obrigatória"));

        return GraphInput.args(Map.of("threadId", input.threadId(), "prompt", prompt));
    }

    @Override
    protected Collection<? extends Event> onNextEvents(RunAgentInput input, NodeOutput<?> output) {
        var events = new ArrayList<Event>(super.onNextEvents(input, output));
        if (!output.isSTART() && !(output instanceof AGUINodeOutput<?>)) {
            events.add(new StateSnapshotEvent(output.state().data()));
        }
        if (output.isEND()) {
            output.state().<String>value("finalMessage").filter(text -> !text.isBlank())
                    .ifPresent(text -> events.addAll(messageEvents(input.runId() + "-final", text)));
        }
        return events;
    }

    @Override
    protected Collection<? extends Event> onCompleteEvents(RunAgentInput input, GraphResult result) {
        if (result.isInterruptionMetadata()) {
            return List.of(interrupted(input, workflow.getState(runnableConfig(input)).state().draft()));
        }
        return super.onCompleteEvents(input, result);
    }

    /** CopilotKit sends an empty run on mount/reload. Rehydrate without resuming the graph. */
    @Override
    public Flow.Publisher<? extends Event> run(RunAgentInput input) {
        if (input.resume().isEmpty() && input.messages().stream().noneMatch(UserMessage.class::isInstance)) {
            return subscriber -> {
                var publisher = new SubmissionPublisher<Event>(Runnable::run, Flow.defaultBufferSize());
                publisher.subscribe(subscriber);
                try {
                    publisher.submit(new RunStartedEvent(input.threadId(), input.runId()));
                    var snapshot = workflow.stateOf(runnableConfig(input));
                    snapshot.ifPresent(item -> {
                        var state = item.state();
                        publisher.submit(new StateSnapshotEvent(state.data()));
                        var messages = new ArrayList<Message>();
                        state.<String>value("prompt").filter(s -> !s.isBlank())
                                .ifPresent(s -> messages.add(new UserMessage("checkpoint-user", s)));
                        state.<String>value("draft").filter(s -> !s.isBlank())
                                .ifPresent(s -> messages.add(new AssistantMessage("checkpoint-draft", s)));
                        state.<String>value("finalMessage").filter(s -> !s.isBlank())
                                .ifPresent(s -> messages.add(new AssistantMessage("checkpoint-final", s)));
                        publisher.submit(new MessagesSnapshotEvent(messages));
                    });
                    if (snapshot.isPresent() && "review".equals(snapshot.orElseThrow().nextNodeId())) {
                        publisher.submit(interrupted(input, snapshot.orElseThrow().state().draft()));
                    } else {
                        publisher.submit(new RunFinishedEvent(input.threadId(), input.runId(),
                                new SuccessOutcome(), null, null, null));
                    }
                } catch (Exception e) {
                    publisher.submit(new RunErrorEvent(Objects.toString(e.getMessage(), "Falha ao restaurar")));
                } finally {
                    publisher.close();
                }
            };
        }
        return super.run(input);
    }

    private static List<Event> messageEvents(String id, String text) {
        return List.of(new TextMessageStartEvent(id, Role.ASSISTANT),
                new TextMessageContentEvent(id, text), new TextMessageEndEvent(id));
    }

    private static RunFinishedEvent interrupted(RunAgentInput input, String draft) {
        var interrupt = new Interrupt(input.threadId() + ":review", "confirmation",
                "Aprovar o rascunho antes de continuar?", null,
                Map.of("type", "object", "properties", Map.of("approved", Map.of("type", "boolean")),
                        "required", List.of("approved")), null, Map.of("draft", draft));
        return new RunFinishedEvent(input.threadId(), input.runId(),
                new InterruptOutcome(List.of(interrupt)), null, null, null);
    }
}
