package dev.dirceu.agent.graph;

import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphResult;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalGraphFactoryTest {

    @Test
    void pausesBeforeHumanReviewAndExecutesOnlyAfterApproval() throws Exception {
        var saver = new MemorySaver();
        var graph = ApprovalGraphFactory.build(
                (prompt, sink) -> {
                    sink.accept("rascunho");
                    return "rascunho";
                }, saver);
        var config = RunnableConfig.builder().threadId("unit-approval").build();

        var run = graph.stream(GraphInput.args(Map.of(
                "threadId", "unit-approval", "prompt", "Minha proposta")), config);
        var visited = run.stream().map(output -> output.node()).toList();

        assertEquals("__START__", visited.getFirst());
        assertTrue(visited.contains("draft"));
        assertFalse(visited.contains("execute"));
        assertTrue(GraphResult.from(run).isInterruptionMetadata());
        assertEquals("review", graph.getState(config).nextNodeId());
        assertEquals("rascunho", graph.getState(config).state().draft());
        assertTrue(graph.getState(config).state().<String>value("finalMessage").isEmpty());

        var resumed = graph.stream(GraphInput.resume(Map.of("approved", true)), config);
        var resumedNodes = resumed.stream().map(output -> output.node()).toList();
        assertTrue(resumedNodes.contains("execute"));
        assertFalse(resumedNodes.contains("reject"));
        assertTrue(graph.getState(config).state().<String>value("finalMessage")
                .orElseThrow().startsWith("Aprovado."));
    }

    @Test
    void rejectionNeverVisitsExecutionNode() throws Exception {
        var graph = ApprovalGraphFactory.build(
                (prompt, sink) -> "proposta", new MemorySaver());
        var config = RunnableConfig.builder().threadId("unit-rejection").build();
        graph.stream(GraphInput.args(Map.of(
                "threadId", "unit-rejection", "prompt", "Tarefa")), config).stream().toList();

        var resumed = graph.stream(GraphInput.resume(Map.of("approved", false)), config);
        var nodes = resumed.stream().map(output -> output.node()).toList();

        assertTrue(nodes.contains("reject"));
        assertFalse(nodes.contains("execute"));
        assertTrue(graph.getState(config).state().<String>value("finalMessage")
                .orElseThrow().contains("Nenhuma ação"));
    }
}
