package dev.dirceu.agent.agui;

import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.*;
import com.agui.community.core.interrupt.*;
import com.agui.community.core.message.UserMessage;
import com.agui.json.AGUIJacksonSerializer;
import dev.dirceu.agent.graph.ApprovalGraphFactory;
import dev.dirceu.agent.graph.DraftGenerator;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalAgUiAgentTest {

    @Test
    void streamsTokensInterruptsRestoresAndResumesWithoutRunningDraftTwice() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        DraftGenerator generator = (prompt, sink) -> {
            calls.incrementAndGet();
            sink.accept("Rascunho ");
            sink.accept("gerado");
            return "Rascunho gerado";
        };
        var graph = ApprovalGraphFactory.build(generator, new MemorySaver());
        var agent = new ApprovalAgUiAgent(graph);
        var threadId = UUID.randomUUID().toString();
        var mount = new RunAgentInput(threadId, UUID.randomUUID().toString(), List.of(), List.of());

        assertInstanceOf(RunFinishedEvent.class, collect(agent.run(mount)).getLast());
        assertEquals(0, calls.get());

        var first = new RunAgentInput(threadId, UUID.randomUUID().toString(),
                List.of(new UserMessage("user-1", "Faça um plano")), List.of());
        var events = collect(agent.run(first));

        assertInstanceOf(RunStartedEvent.class, events.getFirst());
        assertEquals(List.of("Rascunho ", "gerado"), events.stream()
                .filter(TextMessageContentEvent.class::isInstance)
                .map(TextMessageContentEvent.class::cast)
                .map(TextMessageContentEvent::delta).toList());
        assertTrue(events.stream().anyMatch(StateSnapshotEvent.class::isInstance));
        var interruption = assertInstanceOf(InterruptOutcome.class,
                assertInstanceOf(RunFinishedEvent.class, events.getLast()).outcome());
        assertEquals(threadId + ":review", interruption.interrupts().getFirst().id());
        assertEquals("review", graph.getState(RunnableConfig.builder().threadId(threadId).build()).nextNodeId());

        var json = new AGUIJacksonSerializer().serialize(events.getLast());
        assertTrue(json.contains("RUN_FINISHED"));
        assertTrue(json.contains("interrupt"));

        // Refresh returns the pending approval without proceeding past review.
        var reload = collect(agent.run(mount));
        assertTrue(reload.stream().anyMatch(MessagesSnapshotEvent.class::isInstance));
        assertInstanceOf(InterruptOutcome.class,
                assertInstanceOf(RunFinishedEvent.class, reload.getLast()).outcome());
        assertEquals(1, calls.get());

        var resume = new RunAgentInput(threadId, UUID.randomUUID().toString(), null,
                List.of(), List.of(), List.of(), null,
                List.of(new Resume(threadId + ":review", ResumeStatus.RESOLVED,
                        Map.of("approved", true))));
        var completed = collect(agent.run(resume));
        assertInstanceOf(SuccessOutcome.class,
                assertInstanceOf(RunFinishedEvent.class, completed.getLast()).outcome());
        assertTrue(completed.stream().filter(TextMessageContentEvent.class::isInstance)
                .map(TextMessageContentEvent.class::cast)
                .anyMatch(part -> part.delta().startsWith("Aprovado.")));
        assertEquals(1, calls.get());

        var restored = collect(agent.run(mount));
        var messages = restored.stream().filter(MessagesSnapshotEvent.class::isInstance)
                .map(MessagesSnapshotEvent.class::cast).findFirst().orElseThrow();
        assertEquals(3, messages.messages().size());
    }

    @Test
    void declinedApprovalCannotReachExecute() throws Exception {
        var graph = ApprovalGraphFactory.build((prompt, sink) -> "proposta", new MemorySaver());
        var agent = new ApprovalAgUiAgent(graph);
        var threadId = UUID.randomUUID().toString();
        collect(agent.run(new RunAgentInput(threadId, UUID.randomUUID().toString(),
                List.of(new UserMessage("one", "Proposta")), List.of())));

        var resume = new RunAgentInput(threadId, UUID.randomUUID().toString(), null,
                List.of(), List.of(), List.of(), null,
                List.of(new Resume(threadId + ":review", ResumeStatus.RESOLVED,
                        Map.of("approved", false))));
        collect(agent.run(resume));
        var state = graph.getState(RunnableConfig.builder().threadId(threadId).build()).state();
        assertTrue(state.<String>value("finalMessage").orElseThrow().contains("Nenhuma ação"));
    }

    private static List<Event> collect(Flow.Publisher<? extends Event> publisher) throws Exception {
        var collected = new CopyOnWriteArrayList<Event>();
        var completion = new CompletableFuture<Void>();
        publisher.subscribe(new Flow.Subscriber<Event>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(Event item) { collected.add(item); }
            @Override public void onError(Throwable error) { completion.completeExceptionally(error); }
            @Override public void onComplete() { completion.complete(null); }
        });
        completion.get(10, TimeUnit.SECONDS);
        return List.copyOf(collected);
    }
}
