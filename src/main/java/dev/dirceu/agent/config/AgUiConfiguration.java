package dev.dirceu.agent.config;

import com.agui.community.core.serialization.Serializer;
import com.agui.json.AGUIJacksonSerializer;
import dev.dirceu.agent.agui.ApprovalAgUiAgent;
import org.bsc.langgraph4j.agui.sdk.AGUIAgentRegistry;
import org.bsc.langgraph4j.agui.sdk.AGUISSEController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** LangGraph4j owns the AG-UI endpoint; no custom MVC controller is needed. */
@Configuration
@Import(AGUISSEController.class)
public class AgUiConfiguration {

    @Bean
    AGUIAgentRegistry agentRegistry(ApprovalAgUiAgent agent) {
        return new AGUIAgentRegistry(agent);
    }

    @Bean
    Serializer agUiSerializer() {
        return new AGUIJacksonSerializer();
    }
}
