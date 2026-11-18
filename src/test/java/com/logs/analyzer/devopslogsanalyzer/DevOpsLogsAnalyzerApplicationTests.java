package com.logs.analyzer.devopslogsanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.ai.ollama.init.pull-model-strategy=never")
class DevopsLogsAnalyzerApplicationTests {

    @MockitoBean
    VectorStore vectorStore;      // replaces the Chroma store, so no connection

    @Test
    void contextLoads() {}
}