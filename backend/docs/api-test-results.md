# Live API test results

Run `dfa1664451` completed at 2026-10-07T22:35:19.914894+00:00 (2026-10-08 in Riyadh).

**391/391 checks passed; 0 failures. All 65/65 documented operations were exercised against the running Spring Boot API and MySQL 8.4.**

Successful HTTP responses were verified for 62 operations. The remaining three were exercised through rejection guards, as shown below. This is not exhaustive coverage of every parameter combination or an external certification.

## Tested behavior

- JSON login/logout, CSRF rejection, public access, anonymous denial for every protected operation, and role restrictions.
- Section-editor assignment and scope, author ownership, double-blind invitation/file restrictions, single-blind and open identity projections.
- Localized metadata, stale versions, invalid files, byte-exact downloads, annotations, revisions and two review rounds.
- Return-to-author, desk rejection and rejection, copyediting metadata/discussions, private comments, PDF/HTML/XML galleys and proof prerequisites.
- Issue scheduling/publication, continuous publishing, public projections, cover images and immutable published production files.
- All six OAI-PMH verbs through GET and POST, well-formed XML and protocol error cases.
- Preservation inventories and ZIP deposits, including SHA-256 verification of every BagIt manifest entry.
- Bot filtering, duplicate view suppression, human downloads and bounded usage queries.
- Local SMTP notifications captured in Mailpit for this run’s synthetic accounts.

## Remaining coverage limits

- ORCID authorize returns 409 when unconfigured; invalid OAuth state is rejected with 400. Successful external OAuth linking requires credentials and was not attempted.
- Outbox retry rejects a non-DEAD event with 409. Successful DEAD-event replay was not fault-injected against the running journal.
- Live DOI registry acceptance, preservation-network enrollment/harvesting and certified COUNTER reporting were not verified. Existing mocked DOI/outbox unit tests are separate evidence.
- The earlier 10 MySQL Testcontainers cases remain skipped in the image build; this report provides separate live HTTP/MySQL evidence.

## Operation coverage

| Method | Path | Verified result |
|---|---|---|
| DELETE | `/api/admin/roles/{id}` | Success and request checks: 204, 401 |
| GET | `/api/admin/email-templates` | Success and request checks: 200, 401 |
| GET | `/api/admin/outbox` | Success and request checks: 200, 400, 401 |
| GET | `/api/admin/review-forms` | Success and request checks: 200, 401 |
| GET | `/api/admin/settings` | Success and request checks: 200, 401 |
| GET | `/api/admin/usage` | Success and request checks: 200, 400, 401 |
| GET | `/api/admin/users` | Success and request checks: 200, 400, 401, 403 |
| GET | `/api/admin/users/{id}/roles` | Success and request checks: 200, 401 |
| GET | `/api/auth/csrf` | Success and request checks: 200 |
| GET | `/api/editor/reviewers` | Success and request checks: 200, 401 |
| GET | `/api/files/{id}/download` | Success and request checks: 200, 401, 403 |
| GET | `/api/issues` | Success and request checks: 200, 401 |
| GET | `/api/me` | Success and request checks: 200, 401 |
| GET | `/api/me/orcid/callback` | Guard coverage only: 400, 401 |
| GET | `/api/preservation/issues/{id}/inventory` | Success and request checks: 200, 404 |
| GET | `/api/preservation/issues/{id}/manifest` | Success and request checks: 200 |
| GET | `/api/preservation/issues/{id}/package.zip` | Success and request checks: 200 |
| GET | `/api/preservation/manifest` | Success and request checks: 200 |
| GET | `/api/public/articles` | Success and request checks: 200, 400 |
| GET | `/api/public/articles/{id}` | Success and request checks: 200, 404 |
| GET | `/api/public/covers/{id}` | Success and request checks: 200, 404 |
| GET | `/api/public/galleys/{id}/download` | Success and request checks: 200 |
| GET | `/api/public/issues` | Success and request checks: 200 |
| GET | `/api/public/issues/{id}` | Success and request checks: 200, 404 |
| GET | `/api/public/sections` | Success and request checks: 200 |
| GET | `/api/public/settings/ui` | Success and request checks: 200 |
| GET | `/api/reviews` | Success and request checks: 200, 401 |
| GET | `/api/reviews/{id}` | Success and request checks: 200, 401, 403 |
| GET | `/api/submissions` | Success and request checks: 200, 401 |
| GET | `/api/submissions/{id}` | Success and request checks: 200, 401, 403 |
| GET | `/api/submissions/{id}/audit` | Success and request checks: 200, 401 |
| GET | `/api/submissions/{id}/discussions` | Success and request checks: 200, 401 |
| GET | `/api/submissions/{id}/files` | Success and request checks: 200, 401 |
| GET | `/articles/{id}` | Success and request checks: 200 |
| GET | `/oai` | Success and request checks: 200 |
| POST | `/api/admin/outbox/{id}/retry` | Guard coverage only: 401, 409 |
| POST | `/api/admin/review-forms` | Success and request checks: 201, 401 |
| POST | `/api/admin/sections` | Success and request checks: 201, 401, 403 |
| POST | `/api/admin/users` | Success and request checks: 201, 401 |
| POST | `/api/admin/users/{id}/roles` | Success and request checks: 204, 400, 401 |
| POST | `/api/auth/login` | Success and request checks: 204, 401 |
| POST | `/api/auth/logout` | Success and request checks: 204 |
| POST | `/api/auth/register` | Success and request checks: 201, 400, 401, 409 |
| POST | `/api/covers` | Success and request checks: 201, 401 |
| POST | `/api/files/{id}/approve-blind` | Success and request checks: 204, 401 |
| POST | `/api/issues` | Success and request checks: 201, 401 |
| POST | `/api/issues/{id}/articles` | Success and request checks: 204, 401 |
| POST | `/api/issues/{id}/publish` | Success and request checks: 204, 401, 409 |
| POST | `/api/me/orcid/authorize` | Guard coverage only: 401, 409 |
| POST | `/api/reviews/{id}/evaluation` | Success and request checks: 204, 400, 401, 409 |
| POST | `/api/reviews/{id}/response` | Success and request checks: 204, 401, 409 |
| POST | `/api/submissions` | Success and request checks: 201, 401 |
| POST | `/api/submissions/{id}/discussions` | Success and request checks: 201, 401, 403, 409 |
| POST | `/api/submissions/{id}/files` | Success and request checks: 201, 400, 401, 403 |
| POST | `/api/submissions/{id}/publish` | Success and request checks: 204, 401, 409 |
| POST | `/api/submissions/{id}/reviews` | Success and request checks: 201, 401 |
| POST | `/api/submissions/{id}/staff` | Success and request checks: 204, 401 |
| POST | `/api/submissions/{id}/transitions` | Success and request checks: 204, 401, 403, 409 |
| POST | `/oai` | Success and request checks: 200 |
| PUT | `/api/admin/email-templates/{type}` | Success and request checks: 204, 401 |
| PUT | `/api/admin/settings/{key}` | Success and request checks: 204, 401 |
| PUT | `/api/issues/{id}/cover` | Success and request checks: 204, 401 |
| PUT | `/api/submissions/{id}/metadata` | Success and request checks: 204, 401, 409 |
| PUT | `/api/submissions/{id}/review-mode` | Success and request checks: 204, 401, 409 |
| PUT | `/api/submissions/{id}/section-editor` | Success and request checks: 204, 401 |

## Reproduce

Run `MAILPIT_URL=http://localhost:8025 REPORT_DIR=var/api-test-results python3 scripts/test_all_apis.py` against a development journal. Credentials come from `.env` or environment variables. The runner creates uniquely named synthetic data and leaves it in the development database. `Dockerfile.api-test` provides the container runner.

The [raw JSON evidence](api-test-results.json) records every request expectation and assertion without credentials or cookies. The current HTML report is also available at http://localhost:8082/ while `allam-api-tests-final` runs.
