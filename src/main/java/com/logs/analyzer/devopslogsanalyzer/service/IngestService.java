package com.logs.analyzer.devopslogsanalyzer.service;

import com.logs.analyzer.devopslogsanalyzer.config.LogCsvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final int BATCH_SIZE = 50;

    private final VectorStore store;
    private final LogCsvReader reader;
    private final Path sourceDir;

    public IngestService(VectorStore store, LogCsvReader reader,
                         @Value("${personalgpt.source-directory}") String dir) throws IOException {
        this.store = store;
        this.reader = reader;
        this.sourceDir = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(sourceDir);
    }

    /** Folder mode: ingest every *.csv in input_documents/ */
    public synchronized IngestResult ingestFolder() throws IOException {
        List<Path> files;
        try (var s = Files.walk(sourceDir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> isCsv(p.getFileName().toString()))
                    .toList();
        }
        return ingestPaths(files, new ArrayList<>());
    }

    /** Upload mode: save .csv uploads, then ingest them */
    public synchronized IngestResult ingestUploads(List<MultipartFile> uploads) throws IOException {
        List<Path> saved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (MultipartFile mf : uploads) {
            String name = Path.of(Objects.requireNonNullElse(mf.getOriginalFilename(), ""))
                    .getFileName().toString();
            if (!isCsv(name)) {
                skipped.add((name.isBlank() ? "(no name)" : name) + " (only .csv allowed)");
                continue;
            }
            Path target = sourceDir.resolve(name).normalize();
            if (!target.startsWith(sourceDir)) {
                skipped.add(name + " (invalid path)");
                continue;
            }
            mf.transferTo(target);
            saved.add(target);
        }
        return ingestPaths(saved, skipped);
    }

    private IngestResult ingestPaths(List<Path> files, List<String> skipped) {
        int fileCount = 0, rows = 0;
        for (Path p : files) {
            String name = p.getFileName().toString();
            if (!isCsv(name)) {
                skipped.add(name + " (only .csv allowed)");
                continue;
            }
            try {
                List<Document> docs = reader.read(p);
                if (docs.isEmpty()) {
                    skipped.add(name + " (no log rows)");
                    continue;
                }
                for (int i = 0; i < docs.size(); i += BATCH_SIZE) {      // small batches for Ollama
                    store.add(docs.subList(i, Math.min(i + BATCH_SIZE, docs.size())));
                }
                fileCount++;
                rows += docs.size();
            } catch (IllegalArgumentException e) {
                skipped.add(name + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                log.warn("Skipping {}: {}", name, e.getMessage(), e);
                skipped.add(name + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            }
        }
        // No save step: Chroma persists data itself
        return new IngestResult(fileCount, rows, skipped);
    }

    private static boolean isCsv(String name) {
        return name.toLowerCase().endsWith(".csv");
    }

    public record IngestResult(int files, int rows, List<String> skipped) {}
}