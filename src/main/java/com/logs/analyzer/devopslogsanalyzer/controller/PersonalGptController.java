package com.logs.analyzer.devopslogsanalyzer.controller;

import com.logs.analyzer.devopslogsanalyzer.service.IngestService;
import com.logs.analyzer.devopslogsanalyzer.service.QaService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PersonalGptController {

    private final IngestService ingest;
    private final QaService qa;

    public PersonalGptController(IngestService ingest, QaService qa) {
        this.ingest = ingest;
        this.qa = qa;
    }

    // Folder mode: ingest all *.csv in input_documents/
    @PostMapping("/ingest")
    public IngestService.IngestResult ingest() throws IOException {
        return ingest.ingestFolder();
    }

    // Upload mode: send CSV files directly
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestService.IngestResult upload(@RequestParam("files") List<MultipartFile> files) throws IOException {
        return ingest.ingestUploads(files);
    }

    @PostMapping("/ask")
    public QaService.Answer ask(@RequestBody Map<String, String> body) {
        return qa.ask(body.get("query"));
    }

    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String q) {
        return qa.stream(q);
    }
}