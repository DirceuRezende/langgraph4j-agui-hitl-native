package dev.dirceu.agent.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.dirceu.agent.graph.ApprovalGraphFactory;
import dev.dirceu.agent.graph.ApprovalState;
import dev.dirceu.agent.graph.DraftGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.checkpoint.MongoDBSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphConfiguration {
    @Bean(destroyMethod = "close")
    MongoClient mongoClient(@Value("${app.mongo.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean(destroyMethod = "close")
    MongoDBSaver mongoSaver(
            MongoClient mongoClient,
            @Value("${app.mongo.database}") String database) {
        return MongoDBSaver.builder()
                .mongoClient(mongoClient)
                .databaseName(database)
                .collectionPrefix("hitl")
                .stateSerializer(new ObjectStreamStateSerializer<>(ApprovalState::new))
                .build();
    }

    @Bean
    CompiledGraph<ApprovalState> approvalGraph(
            DraftGenerator generator, MongoDBSaver saver) throws GraphStateException {
        return ApprovalGraphFactory.build(generator, saver);
    }
}
