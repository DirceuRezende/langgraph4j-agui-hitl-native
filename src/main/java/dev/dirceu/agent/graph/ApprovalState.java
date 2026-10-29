package dev.dirceu.agent.graph;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public final class ApprovalState extends AgentState {
    public ApprovalState(Map<String, Object> data) {
        super(data);
    }

    public String threadId() {
        return this.<String>value("threadId").orElseThrow();
    }

    public String prompt() {
        return this.<String>value("prompt").orElseThrow();
    }

    public String draft() {
        return this.<String>value("draft").orElseThrow();
    }

    public boolean approved() {
        return this.<Boolean>value("approved").orElse(false);
    }
}
