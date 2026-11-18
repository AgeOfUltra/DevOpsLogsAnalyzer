package com.logs.analyzer.devopslogsanalyzer.config;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LogCsvReader {

    static final List<String> REQUIRED = List.of("Date", "Host", "Service", "Content");

    private static final Pattern FIELD = Pattern.compile(
            "(log\\.type|trace\\.id|event\\.reference|code\\.linenumber)=([^,]+)");
    private static final Pattern FRAME_SPLIT = Pattern.compile("(?:\\t|\\\\t)at ");
    private static final Pattern PNR = Pattern.compile("pnr=<[A-Z0-9]{5,8}>");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    private final int maxBodyChars;
    private final int maxFrames;

    LogCsvReader(@Value("${personalgpt.max-body-chars:1200}") int maxBodyChars,
                 @Value("${personalgpt.max-stack-frames:3}") int maxFrames) {
        this.maxBodyChars = maxBodyChars;
        this.maxFrames = maxFrames;
    }

    /** Throws IllegalArgumentException if the CSV is not the expected log format. */
    public List<Document> read(Path csv) throws IOException {
        List<Document> docs = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).build();

        try (Reader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(r)) {

            List<String> headers = parser.getHeaderNames();
            List<String> missing = REQUIRED.stream().filter(h -> !headers.contains(h)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("missing columns " + missing);
            }

            for (CSVRecord rec : parser) {
                String content = rec.get("Content");
                if (content == null || content.isBlank()) continue;

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("source", csv.getFileName().toString());
                meta.put("date", rec.get("Date"));
                meta.put("host", rec.get("Host"));
                meta.put("service", rec.get("Service"));
                Matcher m = FIELD.matcher(content);
                while (m.find()) meta.putIfAbsent(m.group(1).replace('.', '_'), m.group(2));

                String text = "[" + rec.get("Service") + " " + meta.getOrDefault("log_type", "LOG") + "] "
                        + slim(content);
                docs.add(new Document(sha256(text + meta.get("date") + meta.get("host")), text, meta));
            }
        }
        return docs;
    }

    /** Keep the message, first N stack frames and "Caused by" lines; mask PII; cap length. */
    private String slim(String content) {
        String body = content.contains("greedydata=")
                ? content.substring(content.indexOf("greedydata=") + "greedydata=".length())
                : content;

        String[] parts = FRAME_SPLIT.split(body);
        String message = parts[0].trim();
        String frames = Arrays.stream(parts).skip(1).limit(maxFrames)
                .map(f -> "  at " + f.trim()).collect(Collectors.joining("\n"));
        String caused = Arrays.stream(parts).skip(1)
                .filter(p -> p.contains("Caused by"))
                .map(p -> p.substring(p.indexOf("Caused by")).trim())
                .distinct().limit(2).collect(Collectors.joining("\n"));

        String out = message + (frames.isEmpty() ? "" : "\n" + frames) + (caused.isEmpty() ? "" : "\n" + caused);
        out = PNR.matcher(out).replaceAll("pnr=<REDACTED>");
        out = EMAIL.matcher(out).replaceAll("<EMAIL>");
        return out.length() > maxBodyChars ? out.substring(0, maxBodyChars) + " …" : out;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}