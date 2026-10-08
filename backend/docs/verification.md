# Verification record

Implementation environment: macOS arm64, 2026-10-08. The workspace was initially empty.

## Checks executed

- `python3 scripts/structural_check.py`: passed lexical delimiter/literal checks for 37 Java source files, XML parsing for the Maven POM, JSON/reference/path-parameter checks for 65 OpenAPI operations, SQL DDL parsing and foreign-key target checks for 19 tables in SQLite, and Python smoke-client syntax.
- Ruby YAML parser: application configuration, test configuration and Compose YAML all parse successfully.
- Manual review: ownership/section/staff checks, author/reviewer projections, state transition guards, file revision associations, transaction boundaries, workflow/outbox atomicity, CSRF/session handling, external error redaction and published-only exports.

These are structural checks. SQLite parsing does not establish MySQL compatibility, and delimiter checks do not establish Java compilation.

## Container build and live startup

Using Podman Desktop, the Dockerfile compiled and packaged the application with Java 21 and Maven. The package build ran the tests: 27 passed and 10 MySQL Testcontainers cases were skipped because no Docker daemon was available inside the build container.

Runtime verification on 2026-10-08:

- Image `localhost/allam-journal:dev` built successfully.
- Pod `allam-journal-local` runs the API, MySQL 8.4 and Mailpit, with persistent database and upload volumes.
- Application logs confirm a MySQL JDBC connection, successful Flyway V1 migration and Tomcat startup on port 8080.
- Safari displayed `{"status":"UP"}` at `http://localhost:8080/actuator/health`.
- Safari loaded the live Swagger UI and bundled 65-operation specification at `http://localhost:8080/swagger-ui.html`.
- Host ports 8080 and 8025 are bound to 127.0.0.1; MySQL has no host port.

Local CLI access to Podman's socket and HTTP loopback remains restricted in the execution sandbox. Native Podman Desktop and Safari were used for startup and live verification. The all-API HTTP runner subsequently exercised all 65 documented operations: 391 checks passed with no failures, including live MySQL workflows and run-specific SMTP capture. See [api-test-results.md](api-test-results.md) and [raw evidence](api-test-results.json). Successful responses were verified for 62 operations; the two ORCID routes and outbox retry have guard coverage. External registry/preservation integrations remain unverified. No external service was contacted with credentials and no production deployment was performed.

To stop or restart this local environment, use Podman Desktop → Pods → `allam-journal-local`. Credentials and the private runtime manifest are in ignored `.env` and `var/runtime/` files. Keep these files private.

## Test suites supplied for an equipped environment

| Suite | Coverage |
|---|---|
| WorkflowTest | Full state graph, terminal states, illegal skips, form constraints, translations and XML escaping |
| WorkflowIntegrationTest | File prerequisites, stale versions, section scope/assignment, blind/open projections, review responses, revisions/rounds, publication, bot/duplicate filtering, OAI visibility and signed pagination |
| MySqlIntegrationTest | The same integration scenarios against MySQL 8.4 through Testcontainers; skipped if a Docker daemon is unavailable |
| SecurityIntegrationTest | Authentication, CSRF, privilege escalation rejection, registration roles, JSON session login and logout |
| PublicationAtomicityTest | Rollback of all article states, issue publication, audits and outbox events when a later article fails publication guards |
| DoiRegistryTest | Mock HTTP registration, deterministic DOI targets and no fabricated DOI when unconfigured |
| OutboxWorkerTest | Independent email/plugin fanout, dead-letter retries, safe failure diagnostics and plugin-name validation |
| smoke_workflow.py | Live HTTP flow including two review rounds, revision, copyediting discussion, production, proof approval and issue publication |

Run `mvn test`, then explicitly verify that the MySQL test suite ran instead of being skipped. Use `docker compose up --build`, then `python3 scripts/smoke_workflow.py` against a disposable development environment. External protocol validation and certified COUNTER/preservation-network integrations remain the work described in integrations.md.

## Swagger addition

Added Springdoc 2.9.1, public documentation routes, the curated specification bundled through Maven resources, and a standalone read-only Swagger viewer. Source/spec structural checks and standalone viewer JavaScript syntax checks pass. Security integration tests now assert documentation access and the logout route. The Springdoc dependency compiled successfully, documentation security tests passed, and the live Swagger UI loaded in Safari.
