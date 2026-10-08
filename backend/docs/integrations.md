# Integrations

## DataCite DOI registration

Set `DOI_PROVIDER=datacite`, `DOI_PREFIX`, `DOI_USER`, `DOI_PASSWORD`, `DATACITE_URL`, and the public `PUBLIC_BASE_URL`. DataCite's test endpoint is the default; use a test repository before production. Publication creates a `DOI_REGISTER` outbox job. The service derives `prefix/allam.<submission UUID>`, submits creator/title/publisher/year/type/landing-page metadata with `event=publish`, and records the DOI only after a response confirms the matching identifier is `findable`. A failed registry call does not roll back the article publication. After configuration, replay dead jobs through the manager API.

DataCite supports idempotent PUT creation/update of a specified DOI: [official update API](https://support.datacite.org/docs/updating-metadata-with-the-rest-api), [creation and mandatory attributes](https://support.datacite.org/docs/api-create-dois). The public DOI target is `/articles/{id}`, a crawlable HTML landing page with citation metadata. Crossref's asynchronous XML deposit requires a separate adapter and deposit-status reconciliation; it is not represented as successful just because an upload was accepted. [Crossref registration methods](https://www.crossref.org/documentation/register-maintain-records/choose-content-registration-method/).

## ORCID

Set `ORCID_CLIENT_ID`, `ORCID_CLIENT_SECRET`, `ORCID_BASE_URL`, and the exact registered `ORCID_REDIRECT_URI`. The sandbox URL is the default. While authenticated through the session, POST `/api/me/orcid/authorize`, then navigate to its returned URL. The GET callback exchanges the code server-side, verifies a 10-minute random state bound to the current user, stores the authenticated ORCID plus verification timestamp, and consumes the state. Tokens are not returned to the browser or persisted because `/authenticate` is used only for identity linking. The callback requires the original authenticated session; cookies use SameSite=Lax to allow a top-level OAuth return. [ORCID authenticated identity tutorial](https://info.orcid.org/documentation/api-tutorials/api-tutorial-get-and-authenticated-orcid-id/).

Profile linking is separate from author-entered manuscript metadata; the service does not falsely mark a pasted ORCID as verified. Reviewer assignment excludes the submitting author, the inviting editor, and coauthors matched by email or verified profile ORCID.

## Metadata harvesting

`GET /oai` and form `POST /oai` implement Identify, ListMetadataFormats, ListSets, GetRecord, ListIdentifiers, and ListRecords. Metadata prefix is `oai_dc`; IDs are `oai:allam:<UUID>`; setSpec is a section UUID. Only `PUBLISHED` records exist to harvesters. Both UTC seconds and calendar-day selection are accepted with matching from/until granularity. Page size is 100; tokens bind verb, filters, snapshot upper bound and keyset cursor, use HMAC-SHA256, and expire after one hour. Set `OAI_TOKEN_SECRET` identically on every instance; without it a process-local random secret invalidates outstanding tokens on restart. ListSets is returned as one response.

Examples:

```text
/oai?verb=Identify
/oai?verb=ListRecords&metadataPrefix=oai_dc
/oai?verb=ListRecords&metadataPrefix=oai_dc&from=2026-01-01&until=2026-12-31
/oai?verb=GetRecord&metadataPrefix=oai_dc&identifier=oai:allam:SUBMISSION_UUID
```

OAI protocol errors are XML with HTTP 200. [OAI-PMH specification](https://www.openarchives.org/OAI/openarchivesprotocol.html). An article modified during a multi-page harvest can move beyond the snapshot; incremental harvesters should overlap their date windows, as with ordinary live OAI repositories. Source-schema validation against official XSDs is still needed before registering with an external harvester. Google Scholar primarily uses crawlable article landing pages and citation tags; availability of an OAI endpoint alone does not guarantee Google Scholar/DOAJ indexing.

## Preservation

- `/api/preservation/manifest`: HTML issue and article links.
- `/api/preservation/issues/{id}/manifest`: public galley crawl links.
- `/api/preservation/issues/{id}/inventory`: public metadata, file sizes and SHA-256 checksums.
- `/api/preservation/issues/{id}/package.zip`: streamed BagIt payload with metadata, galleys and SHA-256 manifest.

Published-content only; private manuscripts, reviews, author emails and discussions are excluded. These surfaces can support custom LOCKSS/CLOCKSS crawler plugins and preservation deposit adapters. They do not implement a network-specific permission/enrollment handshake, SWORD deposit lifecycle, receipts, or verification that a deposit has been accepted. PKP-PN is an OJS-specific integration with eligibility and protocol requirements; a custom backend needs a negotiated adapter or an OJS bridge. [PKP's preservation plugin source](https://github.com/pkp/pln). Checksum ZIP export alone is not network compatibility certification.

## Usage

Set `ANALYTICS_SALT` to a random secret. The backend records `ABSTRACT_VIEW` on public JSON/HTML article views and `GALLEY_DOWNLOAD` on public galley requests. Empty agents and common bot/crawler clients are excluded. Same visitor/article/metric/file events within 30 seconds are suppressed under a manuscript lock; UTC day-scoped hashes minimize persistent identifiers. `/api/admin/usage?from=...&until=...` aggregates raw counts and distinct daily visitor hashes.

The 30-second suppression follows [COUNTER's double-click rule](https://cop5.projectcounter.org/en/5.1/07-processing/02-double-click-filtering.html). The heuristic is not the maintained official robot list. The current report is an internal aggregation, not a COUNTER Journal Report, TR_J report, SUSHI endpoint, or audited report. Further work includes standardized reports/metrics, session-level unique investigations/requests, report exceptions, TDM handling, successful-response accounting, robot-list maintenance and independent audit. Requests are counted at authorized response preparation; a subsequent broken streaming connection is not yet reconciled.

## Notifications and extensions

Configure `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_TLS`, and `JOURNAL_EMAIL`. The Compose environment uses Mailpit. Managers may customize subjects/bodies for `STATE_CHANGED`, `REVIEW_INVITED`, `REVIEW_RESPONSE`, `REVIEW_SUBMITTED`, `REVIEW_REMINDER`, `EDITOR_ASSIGNED`, `STAFF_ASSIGNED` and `DISCUSSION_CREATED`. Safe template variables are `{{submissionId}}`, `{{eventType}}`, and `{{state}}`. Templates do not receive reviewer names or confidential content. Invitation/reminder email targets the invited reviewer; editorial-response mail targets editorial users. Internal discussion events honor author/staff versus editor-only visibility. Each workflow state change notifies the author, global editors, assigned section editor and assigned staff as applicable.

A trusted extension implements `JournalPlugin`, is registered as a Spring bean, and is packaged on the application's classpath. Use a unique, short name (at most 53 characters because event types allow 60). `supports(eventType)` determines subscription. `handle(eventId, eventType, submissionId, payload)` performs the integration and must be idempotent. The worker fans out independently retryable plugin jobs. Plagiarism/APC plugins can retrieve authorized internal metadata and make provider calls; webhooks should sign their payloads, verify inbound signatures and deduplicate provider event IDs. Adding/removing extension JARs currently requires an application restart; the boundary provides dependency injection, not runtime executable uploads.

The deployment remains single-journal. For multi-journal hosting, introduce a journal tenant ID on users/grants, manuscripts, issues, templates and all uniqueness/query scopes before sharing one database among journals.

Framework baseline: [Spring Boot 3.5.16 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), with Java 21 and the parent BOM managing compatible dependencies.
