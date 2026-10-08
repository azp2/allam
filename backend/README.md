# Allam Journal Backend

A Spring Boot 3.5.16 / Java 21 / MySQL 8.4 implementation of the OJS manuscript lifecycle. This is a single-journal service with a modular integration boundary. It does not depend on an OJS installation.

The backend implements role grants, scoped editorial assignments, versioned manuscripts and review rounds, blind review projections, copyediting discussions, production galleys, proof approval, issues, continuous publication, and transactional workflow events. Flyway creates the schema; Spring Security provides BCrypt passwords, session login, optional HTTP Basic, and CSRF protection.

## Run locally

Install Docker with Compose, then:

```sh
cp .env.example .env
# Replace every example secret in .env.
docker compose up --build
```

API: http://localhost:8080. Development email inbox: http://localhost:8025. The first launch creates the manager specified by `BOOTSTRAP_EMAIL` and `BOOTSTRAP_PASSWORD`, only when the user table is empty. Bootstrap credentials do not reset an existing account. MySQL and uploads persist in named volumes. The local Compose configuration disables secure cookies for HTTP; deployed environments use HTTPS and secure cookies by default.

For an existing MySQL instance, export `DB_PASSWORD` (and optionally `DB_URL`, `DB_USER`, bootstrap and SMTP settings), then run:

```sh
mvn spring-boot:run
```

## Verify

```sh
mvn test                         # unit + H2 integration + security tests
mvn -Dtest=MySqlIntegrationTest test  # requires a Docker daemon; skips if unavailable
python3 scripts/structural_check.py   # dependency-free source/schema checks
python3 scripts/smoke_workflow.py     # requires the API and bootstrap credentials in .env
MAILPIT_URL=http://localhost:8025 REPORT_DIR=var/api-test-results python3 scripts/test_all_apis.py
```

The smoke client creates isolated test accounts and a submission, performs two review rounds, copyediting, production, proof approval, and issue publication. It writes application data, so run it against a development database.

The all-API runner exercises all 65 documented operations, including successful workflows and denied or invalid requests. It creates synthetic accounts, localized sections, manuscripts and issues with a unique run identifier. JSON and HTML reports distinguish operation coverage from successful responses; unconfigured ORCID and non-DEAD outbox replay have guard coverage. It polls local Mailpit for notifications belonging to that run. Run it only against a development journal. `API_URL` defaults to `http://localhost:8080`; bootstrap credentials are read from the environment or `.env`. `Dockerfile.api-test` provides a Python container runner when local execution is unavailable.

**Verification in the implementation environment:** Built with Java 21 and Maven through Podman Desktop; all 27 non-Docker tests passed, with 10 MySQL Testcontainers tests skipped because the build container has no Docker daemon. The local API started against MySQL 8.4, Flyway applied the schema, health returned `UP`, and Swagger loaded successfully. Live HTTP testing then passed all 391 checks across 65 documented operations (62 with success responses; ORCID and outbox replay guards only). See [API test results](docs/api-test-results.md) and [verification.md](docs/verification.md).

## Browse Swagger / OpenAPI

Open [docs/swagger.html](docs/swagger.html) in a browser to inspect all 65 documented operations immediately, without Java or MySQL. This standalone viewer loads pinned Swagger assets from a CDN and requires internet access. It is read-only.

When the backend is running:

- Swagger UI: http://localhost:8080/swagger-ui.html
- Complete documented OpenAPI JSON (including security-filter logout): http://localhost:8080/openapi.json
- Controller-generated OpenAPI JSON: http://localhost:8080/v3/api-docs

The bundled `docs/openapi.json` is the specification shown in Swagger UI. Maven copies that source into the application's static resources, so there is one maintained copy. The generated `/v3/api-docs` endpoint is useful for inspecting controller-derived schemas; its metadata differs from the curated specification, and logout is implemented by Spring Security rather than a controller.

To try protected endpoints, either use **Authorize → basicAuth** with an existing account, or execute `POST /api/auth/login`. Before every mutation, execute `GET /api/auth/csrf`, copy its `token` into the operation's `X-CSRF-TOKEN` header field, and keep the same browser session. Fetch a new token after login or logout. File uploads use the multipart file picker. Public GET endpoints need no authorization.

Swagger paths are publicly readable; executing protected operations still requires their normal roles and CSRF checks. Setup follows [springdoc's official custom-spec configuration](https://springdoc.org/v2/#how-can-use-custom-jsonyml-file-instead-of-generated-one).

## Use the API

The complete route inventory and request schemas are in [OpenAPI](docs/openapi.json), with workflow rules in [architecture.md](docs/architecture.md) and service configuration in [integrations.md](docs/integrations.md).

1. `GET /api/auth/csrf`, retaining the `JSESSIONID` cookie.
2. `POST /api/auth/login` with JSON `{ "email": "…", "password": "…" }`, the cookie, and the returned `X-CSRF-TOKEN` header.
3. Fetch a new CSRF token after login; login rotates the session and clears the old token.
4. Retain cookies and include CSRF on every POST/PUT/DELETE, including registration and multipart uploads. `POST /oai` is read-only and exempt.
5. `POST /api/auth/logout` ends the session. HTTP Basic is also supported for API clients; it still requires CSRF on mutations.

Public readers can access `/api/public/**`, `/articles/{id}`, `/oai`, and preservation exports without logging in. Every other route requires authentication plus service-level role and resource checks. Registration grants only `READER` and `AUTHOR`. Administrative user creation grants no roles until explicitly assigned.

A draft uses localized metadata:

```json
{
  "sectionId": "section-uuid", "language": "en", "checklist": true,
  "metadata": {
    "title": {"en": "A clinical study", "ar": "دراسة سريرية"},
    "abstract": {"en": "Study abstract", "ar": "ملخص الدراسة"},
    "authors": [{"name": "Researcher", "email": "researcher@example.org", "orcid": "0000-0002-1825-0097", "affiliation": "University"}],
    "keywords": ["medicine"], "funding": [{"name": "Research Council", "award": "123"}],
    "references": ["Reference text"]
  }
}
```

Localized objects are serialized to MySQL `LONGTEXT` through Jackson, not PostgreSQL JSONB. This keeps the same migration usable with the H2 test database. The API validates the metadata envelope and localized title/abstract; funding and references remain extensible arrays. Entered ORCID values are metadata; only OAuth-linked user ORCIDs carry a verification timestamp.

Use `POST /api/submissions/{id}/transitions` with `{ "version": 4, "action": "SUBMIT", "reason": "…" }`. Reload the submission before each command. Uploads, blind approval, metadata edits, editor assignment and transitions advance the version. A stale command receives HTTP 409. Publication uses the dedicated issue/continuous endpoints rather than a general transition.

## Integration scope

- DataCite: automatic asynchronous DOI registration after publication, deterministic identifiers, confirmed registry state, retry/dead-letter handling. Requires a repository account and prefix. Crossref can be implemented through the plugin boundary; a Crossref client is not included.
- ORCID: OAuth authorization-code linking with expiring, single-use, user-bound state. Uses the sandbox by default.
- OAI-PMH: all six verbs, `oai_dc`, section sets, UTC date selection, and signed expiring keyset pagination. Public HTML article pages include scholarly citation metadata.
- Preservation: published-content manifests, checksum inventory, and streaming BagIt issue ZIPs. These are export surfaces for network adapters; they do not claim LOCKSS/CLOCKSS/PKP-PN network enrollment or accepted deposits.
- Usage: public abstract and galley events, heuristic bot exclusion and 30-second duplicate filtering. This is a COUNTER-oriented foundation, **not a certified COUNTER implementation**; standardized reports, SUSHI, official robot-list updates, session rules and audit remain integration work.
- Plugins: trusted Spring beans supplied in extension JARs, independently retried through the outbox. Plagiarism and APC/payment handlers can implement `JournalPlugin`; live untrusted JAR loading and payment-provider clients are not included.

Additional deployment work includes external credentials, email deliverability, backup/restore exercises, upload malware scanning, identity redaction procedures, ingress rate limits, and external protocol certification. File-signature checks are lightweight validation; editorial approval of a blinded file is an explicit human attestation.
