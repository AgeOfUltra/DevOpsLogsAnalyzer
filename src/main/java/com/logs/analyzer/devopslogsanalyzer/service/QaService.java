package com.logs.analyzer.devopslogsanalyzer.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QaService {                           // = myPersonalGPT.py
    private final ChatClient chat;
    private final VectorStore store;
    private final int k;

    QaService(ChatClient.Builder b, VectorStore store,
              @Value("${personalgpt.target-source-chunks}") int k) {
        this.chat = b.build(); this.store = store; this.k = k;
    }

    public record Answer(String answer, List<String> sources) {}

    private List<Document> retrieve(String q) {
        return store.similaritySearch(SearchRequest.builder().query(q).topK(k).build());
    }
    private String prompt(String q, List<Document> docs) {
        String ctx = docs.stream()
                .map(d -> "[" + d.getMetadata().get("service") + " @ " + d.getMetadata().get("date")
                        + " trace=" + d.getMetadata().get("trace_id") + "]\n" + d.getText())
                .collect(Collectors.joining("\n---\n"));
        return """
        You are a DevOps log analyst. Using ONLY the log context below, answer the question.
        State the root cause, the affected service, and a suggested fix.
        If the logs don't contain the answer, say so.

        LOG CONTEXT:
        %s

        QUESTION: %s""".formatted(ctx, q);
    }

    public Answer ask(String q) {
        var docs = retrieve(q);
        var a = chat.prompt().user(prompt(q, docs)).call().content();
        return new Answer(a, docs.stream().map(d -> (String) d.getMetadata().get("source")).distinct().toList());
    }

    public Flux<String> stream(String q) {   // = StreamingStdOutCallbackHandler
        return chat.prompt().user(prompt(q, retrieve(q))).stream().content();
    }
}
