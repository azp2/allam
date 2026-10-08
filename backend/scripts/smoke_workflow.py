#!/usr/bin/env python3
"""Create and exercise a complete development workflow against the running API."""
import http.cookiejar
import json
import os
from pathlib import Path
import urllib.request
import urllib.error
import uuid
from datetime import datetime, timedelta, timezone

BASE = os.environ.get('API_URL', 'http://localhost:8080').rstrip('/')
ENV = {}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            ENV[key.strip()] = value.strip().strip('"').strip("'")

def config(key):
    value = os.environ.get(key, ENV.get(key))
    if not value:
        raise SystemExit(f'Set {key} in your environment or .env')
    return value

class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None
        self.refresh()

    def refresh(self):
        self.csrf = self.call('GET', '/api/auth/csrf')['token']

    def call(self, method, route, body=None, raw=None, content_type=None):
        headers = {'Accept': 'application/json', 'User-Agent': 'allam-smoke-bot'}
        if method not in ('GET', 'HEAD'):
            headers['X-CSRF-TOKEN'] = self.csrf
        data = raw
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            content_type = 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(BASE + route, data=data, headers=headers, method=method)
        try:
            with self.http.open(request, timeout=60) as response:
                value = response.read()
                return json.loads(value) if value else None
        except urllib.error.HTTPError as error:
            # Never echo request credentials.
            raise RuntimeError(f'{method} {route}: HTTP {error.code}: {error.read().decode()}') from error

    def login(self, email, password):
        self.call('POST', '/api/auth/login', {'email': email, 'password': password})
        self.refresh()

    def upload(self, submission, kind, format=None):
        boundary = 'journal-' + uuid.uuid4().hex
        parts = []
        fields = {'kind': kind}
        if format:
            fields['format'] = format
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="manuscript.pdf"\r\nContent-Type: application/pdf\r\n\r\n'.encode() + sample_pdf() + b'\r\n')
        parts.append(f'--{boundary}--\r\n'.encode())
        return self.call('POST', f'/api/submissions/{submission}/files', raw=b''.join(parts), content_type='multipart/form-data; boundary=' + boundary)['id']

    def transition(self, submission, action):
        view = self.call('GET', '/api/submissions/' + submission)
        self.call('POST', f'/api/submissions/{submission}/transitions', {'version': view['version'], 'action': action, 'reason': 'Development workflow smoke test'})

def sample_pdf():
    content = b'BT /F1 12 Tf 50 700 Td (Anonymous study) Tj ET'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>', b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', f'<< /Length {len(content)} >>\nstream\n'.encode() + content + b'\nendstream']
    result = b'%PDF-1.4\n'
    offsets = [0]
    for number, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result += f'{number} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(result)
    result += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
    for offset in offsets[1:]:
        result += f'{offset:010d} 00000 n \n'.encode()
    return result + f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()

def main():
    manager = Client()
    manager.login(config('BOOTSTRAP_EMAIL'), config('BOOTSTRAP_PASSWORD'))
    run = uuid.uuid4().hex[:10]
    password = 'dev-smoke-' + uuid.uuid4().hex
    author = Client()
    author_email = 'author-' + run + '@example.org'
    author.call('POST', '/api/auth/register', {'email': author_email, 'password': password, 'name': 'Smoke Author'})
    author.login(author_email, password)
    users = {}
    clients = {}
    for name, role in [('reviewer', 'REVIEWER'), ('copyeditor', 'COPYEDITOR'), ('layout', 'LAYOUT_EDITOR'), ('proofreader', 'PROOFREADER')]:
        email = name + '-' + run + '@example.org'
        users[name] = manager.call('POST', '/api/admin/users', {'email': email, 'password': password, 'name': 'Smoke ' + name})['id']
        manager.call('POST', '/api/admin/users/' + users[name] + '/roles', {'role': role})
        clients[name] = Client()
        clients[name].login(email, password)
    section = manager.call('POST', '/api/admin/sections', {'names': {'en': 'Smoke Medicine ' + run, 'ar': 'طب'}})['id']
    form = manager.call('POST', '/api/admin/review-forms', {'name': 'Smoke review', 'schema': {'type': 'object', 'properties': {'score': {'type': 'integer', 'minimum': 1, 'maximum': 5}}, 'required': ['score'], 'additionalProperties': False}})['id']
    metadata = {'title': {'en': 'Smoke study ' + run, 'ar': 'دراسة'}, 'abstract': {'en': 'An anonymous study', 'ar': 'ملخص'}, 'authors': [{'name': 'Smoke Author', 'email': author_email}], 'keywords': ['medicine'], 'funding': [], 'references': []}
    submission = author.call('POST', '/api/submissions', {'sectionId': section, 'language': 'en', 'checklist': True, 'metadata': metadata})['id']
    author.upload(submission, 'MANUSCRIPT')
    author.transition(submission, 'SUBMIT')
    for round_number in (1, 2):
        blind = manager.upload(submission, 'BLIND_MANUSCRIPT')
        manager.call('POST', '/api/files/' + blind + '/approve-blind')
        manager.transition(submission, 'SEND_TO_REVIEW' if round_number == 1 else 'START_REVIEW_ROUND')
        assignment = manager.call('POST', f'/api/submissions/{submission}/reviews', {'reviewerId': users['reviewer'], 'dueAt': (datetime.now(timezone.utc) + timedelta(days=10)).isoformat(), 'formId': form})['id']
        reviewer = clients['reviewer']
        invitation = reviewer.call('GET', '/api/reviews/' + assignment)
        assert not any(key in invitation for key in ('files', 'authors', 'formSchema'))
        reviewer.call('POST', '/api/reviews/' + assignment + '/response', {'accept': True})
        review = reviewer.call('GET', '/api/reviews/' + assignment)
        assert 'files' in review and 'authors' not in review
        reviewer.call('POST', '/api/reviews/' + assignment + '/evaluation', {'recommendation': 'MINOR_REVISION' if round_number == 1 else 'ACCEPT', 'answers': {'score': 4}, 'authorComments': 'Clarify methods' if round_number == 1 else 'Approved', 'editorComments': 'Private smoke feedback'})
        author_view = json.dumps(author.call('GET', '/api/submissions/' + submission))
        assert users['reviewer'] not in author_view and 'Private smoke feedback' not in author_view
        if round_number == 1:
            manager.transition(submission, 'REQUEST_REVISIONS')
            author.upload(submission, 'REVISION')
            author.transition(submission, 'SUBMIT_REVISION')
    manager.transition(submission, 'ACCEPT')
    for name, role in [('copyeditor', 'COPYEDITOR'), ('layout', 'LAYOUT_EDITOR'), ('proofreader', 'PROOFREADER')]:
        manager.call('POST', f'/api/submissions/{submission}/staff', {'userId': users[name], 'role': role})
    clients['copyeditor'].call('POST', f'/api/submissions/{submission}/discussions', {'visibility': 'AUTHOR_STAFF', 'body': 'Language correction complete.'})
    clients['copyeditor'].transition(submission, 'COMPLETE_COPYEDITING')
    manager.transition(submission, 'START_PRODUCTION')
    galley = clients['layout'].upload(submission, 'GALLEY', 'PDF')
    clients['proofreader'].transition(submission, 'APPROVE_PROOFS')
    issue = manager.call('POST', '/api/issues', {'volume': 1, 'number': int(run[:6], 16) + 1, 'year': 2026, 'titles': {'en': 'Smoke issue ' + run}})['id']
    manager.call('POST', '/api/issues/' + issue + '/articles', {'submissionId': submission, 'position': 1})
    manager.call('POST', '/api/issues/' + issue + '/publish')
    public = Client().call('GET', '/api/public/articles/' + submission)
    assert public['publishedAt'] and any(item['id'] == galley for item in public['galleys'])
    assert not any(key in public for key in ('reviews', 'state', 'ownerId'))
    print('Workflow smoke test completed:', json.dumps({'submissionId': submission, 'issueId': issue, 'galleyId': galley}))

if __name__ == '__main__':
    main()
