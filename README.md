# DevOps Logs Analyzer

A private, fully local **RAG (Retrieval-Augmented Generation)** service that lets you ask questions about your application logs in plain English. Built with **Java 21, Spring Boot and Spring AI**. No data leaves your machine: the LLM runs locally through **Ollama** (`mistral`), embeddings come from Ollama (`nomic-embed-text`), and vectors are stored in **Chroma**.

> Java / Spring AI re-implementation of a Python privateGPT-style project (LangChain + Chroma + Ollama), specialised for log analysis. See [Credits](#credits).

## How it works

```
 input_documents/*.csv
          |
          v
   LogCsvReader  -- 1 CSV row = 1 Document, trimmed stack trace, PII masked
          |
          v
   Ollama (nomic-embed-text)  -- embeddings
          |
          v
   Chroma vector store  <------ similarity search (top-k) ------+
                                                                |
   POST /api/ask  -->  retrieve relevant log rows  -->  Ollama (mistral)  -->  answer + sources
```

1. **Ingest:** each row of a CSV log export becomes one document. Long stack traces are trimmed to the error message, the first few frames and the `Caused by` lines. Booking references (PNR) and email addresses are masked before anything is stored.
2. **Ask:** your question is embedded, the most similar log rows are retrieved from Chroma, and `mistral` answers using only that context (root cause, affected service, suggested fix).

## Tech stack

| Component | Version / choice |
|---|---|
| Language / build | Java 21, Gradle |
| Framework | Spring Boot 4.1.2 |
| AI | Spring AI 2.0.1 |
| LLM | **Ollama + `mistral`** (runs locally) |
| Embeddings | Ollama + `nomic-embed-text` |
| Vector store | Chroma 0.6.3 (Docker) |
| CSV parsing | Apache Commons CSV |
| Observability | Spring Boot Actuator + Prometheus metrics |

## Prerequisites

- **JDK 21** (`java -version`)
- **Docker** (for Chroma)
- **[Ollama](https://ollama.com/download)** installed and running

## Project structure

```
devopsLogsAnalyzer/
├── input_documents/          <-- PUT YOUR CSV LOG FILES HERE (project root)
│   └── sample-logs.csv
├── build.gradle
└── src/main/
    ├── java/com/logs/analyzer/devopslogsanalyzer/
    │   ├── DevopsLogsAnalyzerApplication.java
    │   ├── controller/PersonalGptController.java
    │   ├── service/IngestService.java
    │   ├── service/QaService.java
    │   └── config/LogCsvReader.java
    └── resources/
```

## Input files: the `input_documents/` folder

Create a folder named **`input_documents`** in the **root directory of the project** (next to `build.gradle`) and drop your log exports there:

```bash
mkdir -p input_documents
cp /path/to/your-export.csv input_documents/
```

- Only **`.csv`** files are supported. Any other file type is skipped and reported in the API response.
- The path is configurable via `personalgpt.source-directory` or the `SOURCE_DIRECTORY` environment variable. The app resolves it relative to where you start it, so run it from the project root.
- Re-ingesting the same file does not create duplicates (document IDs are a hash of the row).

## CSV file format

The reader expects a Datadog-style log export with a header row containing **at least** these columns:

| Column | Required | Description |
|---|---|---|
| `Date` | yes | Timestamp of the log entry |
| `Host` | yes | Host or container name |
| `Service` | yes | Service name |
| `Content` | yes | The raw log line (may contain a long stack trace) |
| `greedydata` | optional | Extra parsed field (ignored; the text is read from `Content`) |

A CSV missing any required column is rejected, and the response tells you which columns are missing.

From `Content`, the reader extracts these key/value pairs when present and stores them as metadata: `log.type`, `trace.id`, `event.reference`, `code.linenumber`.

### Sample (`input_documents/sample-logs.csv`)

Use fake data like this in any public repo. Stack frames are split on a tab followed by `at ` (the literal text `\tat ` also works):

```csv
Date,Host,Service,greedydata,Content
"2026-11-18T08:30:14.812Z","host-1","demo-service",,"2026-11-18T08:30:13.523+0000,log.type=ERROR,trace.id=abc123,event.reference=OrderCommand,code.linenumber=94,greedydata=Failed to execute: INVALID_ARGUMENT: Cannot select more than one bundle meal per flight per pax.\tat com.demo.OrderCommand.run(OrderCommand.java:32)\tat com.demo.Service.call(Service.java:10)"
"2026-11-18T08:31:02.100Z","host-1","demo-service",,"2026-11-18T08:31:01.900+0000,log.type=WARN,trace.id=def456,event.reference=PaymentClient,code.linenumber=51,greedydata=Payment gateway timeout after 30s for pnr=<ABC123>, retrying\tat com.demo.PaymentClient.pay(PaymentClient.java:51)"
```

> **Privacy:** real log exports can contain booking references, hostnames, emails and internal registry paths. The reader masks PNRs and emails, but do **not** commit real exports to a public repository.

## Ollama and the Mistral model

Ollama runs open-source LLMs locally. This project uses two models:

| Purpose | Model | Pull command |
|---|---|---|
| Answering questions (chat) | **`mistral`** | `ollama pull mistral` |
| Embeddings | `nomic-embed-text` | `ollama pull nomic-embed-text` |

```bash
ollama pull mistral
ollama pull nomic-embed-text
ollama list                      # both should appear
curl http://localhost:11434      # prints "Ollama is running"
```

To try another chat model without editing files:

```bash
ollama pull llama3.2
MODEL=llama3.2 ./gradlew bootRun
```

If you change the embedding model, the vector size changes. Delete and recreate the Chroma collection and re-ingest.

> First answers are slow on CPU-only machines (a minute or two). HTTP read timeouts are set to 300 seconds.

## Setup and run

### 1. Start Chroma

```bash
docker compose up -d chroma
curl http://localhost:8000/api/v2/version     # prints "0.6.3"
```

Chroma runs on port 8000. Use version **0.6.3**; newer tags were not tested with Spring AI 2.0.1.

### 2. Create the collection (one time)

Spring AI 2.0.1's Chroma client throws on a missing collection instead of creating it (known issue: [spring-projects/spring-ai#4845](https://github.com/spring-projects/spring-ai/issues/4845)), so the collection is created once by hand in Chroma's default tenant and database:

```bash
curl -s -X POST \
  localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections \
  -H 'Content-Type: application/json' \
  -d '{"name":"devops-logs"}'
```

Verify:

```bash
curl -s localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections
```

### 3. Configuration

Defaults work out of the box on a local machine. Override them with environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `MODEL` | `mistral` | Ollama chat model |
| `EMBEDDING_MODEL` | `nomic-embed-text` | Ollama embedding model |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama server |
| `CHROMA_HOST` / `CHROMA_PORT` | `http://localhost` / `8000` | Chroma server |
| `SOURCE_DIRECTORY` | `input_documents` | Folder scanned for CSV files |
| `TARGET_SOURCE_CHUNKS` | `4` | Number of log rows retrieved per question |

The Chroma collection is `devops-logs` in the `default_tenant` / `default_database` (the ones created in step 2).

### 4. Build and run

```bash
./gradlew clean build -x test
./gradlew bootRun
```

The app listens on port **8080**.

## API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/ingest` | Ingest all `.csv` files from `input_documents/` |
| `POST` | `/api/upload` | Upload one or more `.csv` files (multipart field `files`) and ingest them |
| `POST` | `/api/ask` | Ask a question. Body: `{"query": "..."}` |
| `GET` | `/api/ask/stream?q=...` | Same as ask, streamed (Server-Sent Events) |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### Examples

```bash
# 1. Ingest the CSVs in input_documents/
curl -X POST http://localhost:8080/api/ingest
# {"files":1,"rows":4,"skipped":[]}

# 2. Or upload a file directly
curl -X POST http://localhost:8080/api/upload -F "files=@/path/to/export.csv"

# 3. Ask a question
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"query":"Why did the ancillary modification fail and how do I fix it?"}'
# {"answer":"...root cause, affected service, suggested fix...","sources":["export.csv"]}
```

Example questions:

- *Find the 500 or ERROR entries and explain the root cause.*
- *Which services and trace IDs are affected?*
- *Is this an outage or a validation error?*

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Collection devops-logs does not exist` at startup | Collection not created, or the app is using a different tenant/database than the one it was created in. Redo setup step 2 |
| `Connection refused localhost:8000` | Chroma isn't running: `docker compose up -d chroma` |
| `Connection refused localhost:11434` | Ollama isn't running: start it and check `curl localhost:11434` |
| `model "mistral" not found` | Run `ollama pull mistral` (and `ollama pull nomic-embed-text`) |
| Ingest returns `skipped: ["x (missing columns [...])"]` | CSV is missing a required column (see [CSV file format](#csv-file-format)) |
| Ingest returns `0 files` | No `.csv` in `input_documents/`, or the app was started from a different directory |
| Answers say "I don't know" | Nothing ingested yet, or the question isn't covered by the logs |
| Very slow first answer | Normal on CPU. Try a smaller model, e.g. `MODEL=llama3.2` |
| Tests fail without Chroma/Ollama | Run `./gradlew build -x test`, or mock the vector store in tests |

## Security notes

- `/api/ingest` and `/api/upload` have **no authentication**. Add Spring Security or an API key before exposing the service beyond localhost.
- Logs are untrusted text placed into the LLM prompt, so a malicious log line could try to steer the model (prompt injection). Treat answers as assistance, not ground truth.
- Real log exports may contain sensitive data. Keep them out of version control (see `.gitignore` below).

Suggested `.gitignore` entries:

```
input_documents/*.csv
!input_documents/sample-logs.csv
build/
.gradle/
```

## Roadmap

- Dockerfile for the app
- GitHub Actions CI: build, test, Docker image, Trivy and dependency scanning
- Prometheus and Grafana dashboards for request latency
- Authentication on ingest/upload endpoints

## Credits

Inspired by [privateGPT](https://github.com/imartinez/privateGPT) and [Ollama](https://ollama.com), and by the Python PersonalGPT project from the *Generative AI for DevOps and DevSecOps Engineers* course by ASecurityGuru. This repository is an independent Java / Spring AI re-implementation focused on CSV log analysis.
