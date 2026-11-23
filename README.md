# PredLock Extractor

PredLock Extractor is a static analysis component for Java/JPA applications that extracts entity models, transaction boundaries, SQL operations, and resolved transaction scopes from source code. It combines the extracted information and produces the input JSON consumed by the PredLock deadlock analyzer in this paper's artifact pipeline.

## Requirements

- Java 17 or later
- Apache Maven 3.9 or later
- Docker (optional, for containerized builds and execution)

The project uses Java 17 language settings and Maven for dependency management and packaging.

## Build and Run

Build the executable JAR:

```bash
mvn clean package
```

Run the extractor:

```bash
java -jar target/predlock-extractor-1.0-SNAPSHOT-jar-with-dependencies.jar <project-path> [output-path]
```

`<project-path>` is required and must be an existing Java project directory. `[output-path]` is optional; it defaults to `deadlock-analyzer-input.json` in the current directory.

Run the tests:

```bash
mvn test
```

### Docker

Build the image locally:

```bash
docker build -t predlock-extractor .
```

Docker Hub image:

```text
rawanalkhalawi/predlock-extractor:latest
```

Run the Docker image against an application source directory:

```bash
docker run --rm \
  -v /absolute/path/to/application:/project \
  -v "$PWD:/output" \
  rawanalkhalawi/predlock-extractor:latest \
  /project /output/deadlock-analyzer-input.json
```

## Usage Example

Given a JPA/Spring application stored at `/tmp/my-spring-app`, run:

```bash
java -jar target/predlock-extractor-1.0-SNAPSHOT-jar-with-dependencies.jar \
  /tmp/my-spring-app \
  /tmp/deadlock-analyzer-input.json
```

The input is the application's source tree. The extractor analyzes JPA entity annotations and relationships, `@Transactional` methods, repository and SQL operations, and call graph propagation.

The output is a JSON document with resolved transactions and SQL statements, for example:

```json
{
  "transactions": [
    {
      "id": "T1_transfer",
      "beginTimestamp": 4000,
      "statements": [
        {
          "executionOrder": 1,
          "type": "UPDATE",
          "rawSql": "UPDATE account SET balance = ?",
          "tableNames": ["account"],
          "queryConditions": "",
          "parameters": {},
          "resultReference": "res1",
          "lineNumber": 42,
          "triggeringCodeLocation": "com.example.AccountService.transfer"
        }
      ],
      "pathConditions": []
    }
  ]
}
```

The exact transaction IDs, SQL operations, table names, and line numbers depend on the analyzed source project.

## Paper

This repository accompanies the paper "PredLock: Static Code Analysis for Detecting Database Deadlocks in CI/CD Pipelines" (under review).

## AI Assistance

Parts of the code in this repository were developed with the assistance of AI tools (Claude and ChatGPT). All generated code was reviewed, tested, and validated by the authors.
