CREATE TABLE app_user (
 id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, orcid VARCHAR(19) UNIQUE,
 orcid_verified_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE section (
 id VARCHAR(36) PRIMARY KEY, names LONGTEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE user_role (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, role VARCHAR(30) NOT NULL,
 section_id VARCHAR(36) NULL,
 FOREIGN KEY (user_id) REFERENCES app_user(id), FOREIGN KEY (section_id) REFERENCES section(id)
);
CREATE INDEX ix_role_user ON user_role(user_id);
CREATE TABLE submission (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, section_id VARCHAR(36) NOT NULL,
 assigned_editor_id VARCHAR(36) NULL, language VARCHAR(16) NOT NULL,
 checklist BOOLEAN NOT NULL, state VARCHAR(40) NOT NULL, review_mode VARCHAR(20) NOT NULL,
 metadata LONGTEXT NOT NULL, round_no INT NOT NULL DEFAULT 1, revision_no INT NOT NULL DEFAULT 1,
 version BIGINT NOT NULL DEFAULT 0, doi VARCHAR(255) UNIQUE,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, published_at TIMESTAMP(6) NULL,
 FOREIGN KEY(owner_id) REFERENCES app_user(id), FOREIGN KEY(section_id) REFERENCES section(id),
 FOREIGN KEY(assigned_editor_id) REFERENCES app_user(id)
);
CREATE INDEX ix_submission_queue ON submission(state,section_id);
CREATE INDEX ix_submission_owner ON submission(owner_id);
CREATE TABLE workflow_audit (
 id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL,
 action VARCHAR(40) NOT NULL, from_state VARCHAR(40) NOT NULL, to_state VARCHAR(40) NOT NULL,
 reason TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(submission_id) REFERENCES submission(id), FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE submission_file (
 id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36) NOT NULL, uploader_id VARCHAR(36) NOT NULL,
 kind VARCHAR(30) NOT NULL, format VARCHAR(20) NULL, revision_no INT NOT NULL,
 original_name VARCHAR(255) NOT NULL, media_type VARCHAR(100) NOT NULL,
 storage_key VARCHAR(36) NOT NULL UNIQUE, size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 blind_approved BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(submission_id) REFERENCES submission(id), FOREIGN KEY(uploader_id) REFERENCES app_user(id)
);
CREATE INDEX ix_file_submission ON submission_file(submission_id,kind,revision_no);
CREATE TABLE review_form (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(200) NOT NULL, schema_json LONGTEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE review_assignment (
 id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36) NOT NULL, reviewer_id VARCHAR(36) NOT NULL,
 round_no INT NOT NULL, revision_no INT NOT NULL, status VARCHAR(20) NOT NULL,
 due_at TIMESTAMP(6) NOT NULL, form_schema LONGTEXT NOT NULL,
 recommendation VARCHAR(25) NULL, answers LONGTEXT NULL, author_comments TEXT NULL,
 editor_comments TEXT NULL, submitted_at TIMESTAMP(6) NULL,
 UNIQUE(submission_id,reviewer_id,round_no),
 FOREIGN KEY(submission_id) REFERENCES submission(id), FOREIGN KEY(reviewer_id) REFERENCES app_user(id)
);
CREATE INDEX ix_review_due ON review_assignment(status,due_at);
CREATE TABLE review_file (
 assignment_id VARCHAR(36) NOT NULL, file_id VARCHAR(36) NOT NULL UNIQUE,
 FOREIGN KEY(assignment_id) REFERENCES review_assignment(id), FOREIGN KEY(file_id) REFERENCES submission_file(id)
);
CREATE TABLE staff_assignment (
 submission_id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL, role VARCHAR(30) NOT NULL,
 PRIMARY KEY(submission_id,user_id,role), FOREIGN KEY(submission_id) REFERENCES submission(id),
 FOREIGN KEY(user_id) REFERENCES app_user(id)
);
CREATE TABLE discussion (
 id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36) NOT NULL, author_id VARCHAR(36) NOT NULL,
 visibility VARCHAR(20) NOT NULL, body TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(submission_id) REFERENCES submission(id), FOREIGN KEY(author_id) REFERENCES app_user(id)
);
CREATE TABLE issue_cover (
 id VARCHAR(36) PRIMARY KEY, storage_key VARCHAR(36) NOT NULL UNIQUE, media_type VARCHAR(100) NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL
);
CREATE TABLE issue (
 id VARCHAR(36) PRIMARY KEY, volume INT NOT NULL, issue_number INT NOT NULL, issue_year INT NOT NULL,
 titles LONGTEXT NOT NULL, cover_file_id VARCHAR(36) NULL, published_at TIMESTAMP(6) NULL,
 UNIQUE(volume,issue_number,issue_year), FOREIGN KEY(cover_file_id) REFERENCES issue_cover(id)
);
CREATE TABLE issue_article (
 issue_id VARCHAR(36) NOT NULL, submission_id VARCHAR(36) NOT NULL UNIQUE, position_no INT NOT NULL,
 PRIMARY KEY(issue_id,submission_id), FOREIGN KEY(issue_id) REFERENCES issue(id), FOREIGN KEY(submission_id) REFERENCES submission(id)
);
CREATE TABLE journal_setting (setting_key VARCHAR(100) PRIMARY KEY, value_json LONGTEXT NOT NULL);
CREATE TABLE email_template (
 event_type VARCHAR(60) PRIMARY KEY, subject VARCHAR(255) NOT NULL, body TEXT NOT NULL
);
CREATE TABLE outbox (
 id VARCHAR(36) PRIMARY KEY, event_type VARCHAR(60) NOT NULL, aggregate_id VARCHAR(36) NOT NULL,
 payload LONGTEXT NOT NULL, dedupe_key VARCHAR(255) NOT NULL UNIQUE,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 available_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL, last_error TEXT NULL
);
CREATE INDEX ix_outbox_ready ON outbox(status,available_at);
CREATE TABLE orcid_state (
 state_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES app_user(id)
);
CREATE TABLE usage_event (
 id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36) NOT NULL, file_id VARCHAR(36) NULL,
 metric VARCHAR(30) NOT NULL, visitor_hash VARCHAR(64) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(submission_id) REFERENCES submission(id), FOREIGN KEY(file_id) REFERENCES submission_file(id)
);
CREATE INDEX ix_usage_dedupe ON usage_event(visitor_hash,submission_id,metric,occurred_at);
