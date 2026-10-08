#!/usr/bin/env python3
"""Real HTTP contract and workflow tests. Creates only uniquely named synthetic data.

Run with API_URL, BOOTSTRAP_EMAIL and BOOTSTRAP_PASSWORD, or local .env.
Reports never contain passwords, CSRF tokens or session cookies. The optional
REPORT_PORT serves the final report for inspection when running in Podman.
"""
import base64
import hashlib
import html
import http.cookiejar
import http.server
import io
import json
import os
from pathlib import Path
import re
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timedelta, timezone
from smoke_workflow import sample_pdf

BASE = os.getenv('API_URL', 'http://localhost:8080').rstrip('/')
SPEC = json.loads((Path(__file__).resolve().parent.parent / 'docs/openapi.json').read_text())
OPERATIONS = {(method.upper(), path) for path, methods in SPEC['paths'].items()
              for method in methods if method.lower() in ('get', 'post', 'put', 'delete', 'patch')}
RESULTS = []
COVERED = set()
SUCCESS = set()
RUN = uuid.uuid4().hex[:10]


def config(key):
    if os.getenv(key):
        return os.environ[key]
    env = Path('.env')
    if env.exists():
        for line in env.read_text().splitlines():
            if line.startswith(key + '='):
                return line.split('=', 1)[1].strip().strip('\"').strip("'")
    raise RuntimeError('Missing configuration: ' + key)


def check(label, condition):
    RESULTS.append({'test': label, 'passed': bool(condition)})
    if not condition:
        print('FAIL ASSERT ' + label, flush=True)


def match(method, path):
    path = urllib.parse.urlsplit(path).path
    for candidate in OPERATIONS:
        if candidate[0] == method and re.fullmatch(re.sub(r'\{[^}]+\}', '[^/]+', candidate[1]), path):
            return candidate
    return None


class Client:
    def __init__(self, role='anonymous'):
        self.role = role
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.csrf = None
        self.refresh()

    def request(self, method, path, body=None, expected=200, raw=None, content_type=None,
                csrf=True, label=None, agent='allam-api-test-bot'):
        headers = {'Accept': '*/*', 'User-Agent': agent}
        if method not in ('GET', 'HEAD') and csrf and self.csrf:
            headers['X-CSRF-TOKEN'] = self.csrf
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode()
            content_type = 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        req = urllib.request.Request(BASE + path, data=raw, method=method, headers=headers)
        try:
            response = self.http.open(req, timeout=30)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            status, data, headers = response.code, response.read(), dict(response.headers)
        wanted = [expected] if isinstance(expected, int) else expected
        passed = status in wanted
        op = match(method, path)
        if op:
            COVERED.add(op)
            if passed and 200 <= status < 300:
                SUCCESS.add(op)
        name = label or f'{self.role}: {method} {path}'
        RESULTS.append({'test': name, 'method': method, 'path': path,
                        'expected': wanted, 'status': status, 'passed': passed})
        if not passed:
            # Response bodies are intentionally omitted: do not echo tokens or credentials.
            print(f'FAIL {name}: expected {wanted}, received {status}', flush=True)
        if 'json' in headers.get('Content-Type', '') and data:
            value = json.loads(data)
        else:
            value = data
        return value

    def refresh(self):
        value = self.request('GET', '/api/auth/csrf')
        self.csrf = value['token']

    def login(self, email, password):
        self.request('POST', '/api/auth/login', {'email': email, 'password': password}, expected=204)
        self.refresh()

    def upload(self, submission=None, kind=None, format=None, assignment=None,
               filename='manuscript.pdf', data=None, media='application/pdf', expected=201):
        boundary = 'test-' + uuid.uuid4().hex
        fields = {k: v for k, v in {'kind': kind, 'format': format, 'assignmentId': assignment}.items() if v}
        chunks = [f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items()]
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {media}\r\n\r\n'.encode() + (sample_pdf() if data is None else data) + b'\r\n')
        chunks.append(f'--{boundary}--\r\n'.encode())
        route = f'/api/submissions/{submission}/files' if submission else '/api/covers'
        value = self.request('POST', route, expected=expected, raw=b''.join(chunks), content_type='multipart/form-data; boundary=' + boundary)
        return value.get('id') if isinstance(value, dict) else None

    def transition(self, sid, action, expected=204, version=None):
        view = self.request('GET', '/api/submissions/' + sid)
        self.request('POST', f'/api/submissions/{sid}/transitions',
                     {'version': view['version'] if version is None else version, 'action': action,
                      'reason': 'Synthetic API verification ' + RUN}, expected=expected)


def phase(name):
    print('PHASE ' + name, flush=True)


def main():
    phase('authentication and anonymous access')
    anonymous = Client()
    anonymous.request('GET', '/actuator/health')
    anonymous.request('GET', '/openapi.json')
    anonymous.request('GET', '/v3/api-docs')
    anonymous.request('GET', '/swagger-ui.html')
    # Every authenticated operation must reject an anonymous session even with valid CSRF.
    for method, route in sorted(OPERATIONS):
        if route.startswith(('/api/public/', '/api/preservation/', '/articles/', '/oai', '/api/auth/')):
            continue
        path = re.sub(r'\{[^}]+\}', 'missing', route)
        anonymous.request(method, path, body={} if method in ('POST', 'PUT') else None,
                          expected=401, label='anonymous denied: ' + method + ' ' + route)
    anonymous.request('POST', '/api/auth/register', {}, expected=401, csrf=False,
                      label='Anonymous registration without CSRF is rejected')
    anonymous.request('POST', '/api/auth/register', {'email': 'invalid', 'password': 'short', 'name': ''}, expected=400)
    anonymous.request('POST', '/api/auth/login', {'email': 'absent@example.org', 'password': 'incorrect-password'}, expected=401)
    manager = Client('manager')
    manager.login(config('BOOTSTRAP_EMAIL'), config('BOOTSTRAP_PASSWORD'))
    manager.request('POST', '/api/admin/sections', {'names': {'en': 'Forbidden CSRF'}}, expected=403, csrf=False, label='Authenticated mutation requires CSRF')
    me = manager.request('GET', '/api/me')
    check('Manager profile never exposes password hash', 'password' not in json.dumps(me).lower())
    password = 'api-test-' + uuid.uuid4().hex
    author_email = 'api-author-' + RUN + '@example.org'
    author = Client('author')
    author_id = author.request('POST', '/api/auth/register', {'email': author_email, 'password': password, 'name': 'API Author ' + RUN}, expected=201)['id']
    author.request('POST', '/api/auth/register', {'email': author_email, 'password': password, 'name': 'Duplicate'}, expected=409)
    author.login(author_email, password)
    users, clients = {}, {}
    for name, role in [('reviewer', 'REVIEWER'), ('decliner', 'REVIEWER'), ('copyeditor', 'COPYEDITOR'),
                       ('layout', 'LAYOUT_EDITOR'), ('proofreader', 'PROOFREADER'), ('section', 'SECTION_EDITOR'),
                       ('othersection', 'SECTION_EDITOR'), ('reader', 'READER'), ('outsider', 'AUTHOR')]:
        email = f'api-{name}-{RUN}@example.org'
        users[name] = manager.request('POST', '/api/admin/users', {'email': email, 'password': password, 'name': 'API ' + name + ' ' + RUN}, expected=201)['id']
        if role != 'SECTION_EDITOR':
            manager.request('POST', f'/api/admin/users/{users[name]}/roles', {'role': role}, expected=204)
        clients[name] = Client(name)
        clients[name].login(email, password)
    phase('administration, scope and validation')
    section = manager.request('POST', '/api/admin/sections', {'names': {'en': 'API Medicine ' + RUN, 'ar': 'الطب'}}, expected=201)['id']
    other_section = manager.request('POST', '/api/admin/sections', {'names': {'en': 'API Other ' + RUN}}, expected=201)['id']
    for name, sid in [('section', section), ('othersection', other_section)]:
        manager.request('POST', f'/api/admin/users/{users[name]}/roles', {'role': 'SECTION_EDITOR', 'sectionId': sid}, expected=204)
    manager.request('GET', '/api/admin/users')
    roles = manager.request('GET', f'/api/admin/users/{users["reader"]}/roles')
    manager.request('DELETE', '/api/admin/roles/' + roles[0]['id'], expected=204)
    manager.request('POST', f'/api/admin/users/{users["reader"]}/roles', {'role': 'READER'}, expected=204)
    manager.request('POST', f'/api/admin/users/{users["reader"]}/roles', {'role': 'SECTION_EDITOR'}, expected=400)
    manager.request('GET', '/api/admin/users?page=-1', expected=400)
    manager.request('GET', '/api/admin/outbox?size=101', expected=400)
    author.request('GET', '/api/admin/users', expected=403)
    clients['reader'].request('POST', '/api/admin/sections', {'names': {'en': 'Forbidden'}}, expected=403)
    schema = {'type': 'object', 'properties': {'score': {'type': 'integer', 'minimum': 1, 'maximum': 5}}, 'required': ['score'], 'additionalProperties': False}
    form = manager.request('POST', '/api/admin/review-forms', {'name': 'API review ' + RUN, 'schema': schema}, expected=201)['id']
    manager.request('GET', '/api/admin/review-forms')
    manager.request('GET', '/api/editor/reviewers?query=API')
    manager.request('PUT', '/api/admin/settings/api-test.' + RUN, {'en': 'Synthetic setting', 'ar': 'اختبار'}, expected=204)
    settings = manager.request('GET', '/api/admin/settings')
    check('Localized setting persisted', any(x['setting_key'] == 'api-test.' + RUN for x in settings))
    manager.request('PUT', '/api/admin/email-templates/API_TEST_' + RUN.upper().translate(str.maketrans('0123456789', 'GHIJKLMNOP')), {'subject': 'Synthetic test', 'body': 'Synthetic test template'}, expected=204)
    manager.request('GET', '/api/admin/email-templates')
    anonymous.request('GET', '/api/public/settings/ui')
    anonymous.request('GET', '/api/public/sections')
    # No OAuth credentials are transmitted to external services.
    author.request('POST', '/api/me/orcid/authorize', expected=409, label='ORCID unconfigured guard (external success not tested)')
    author.request('GET', '/api/me/orcid/callback?state=invalid&code=invalid', expected=400)
    phase('draft, files and editorial triage')
    metadata = {'title': {'en': 'API Study ' + RUN + ' & medicine', 'ar': 'دراسة'}, 'abstract': {'en': 'Synthetic anonymous study', 'ar': 'ملخص'},
                'authors': [{'name': 'API Author', 'email': author_email, 'orcid': '0000-0002-1825-0097'}], 'keywords': ['medicine'], 'funding': [], 'references': []}
    def draft(suffix=''):
        data = dict(metadata)
        data['title'] = {'en': 'API Study ' + RUN + suffix, 'ar': 'دراسة'}
        return author.request('POST', '/api/submissions', {'sectionId': section, 'language': 'en', 'checklist': True, 'metadata': data}, expected=201)['id']
    sid = draft()
    author.request('GET', '/api/submissions')
    clients['outsider'].request('GET', '/api/submissions/' + sid, expected=403)
    clients['reader'].request('GET', '/api/submissions/' + sid, expected=403)
    anonymous.request('GET', '/api/public/articles/' + sid, expected=404)
    author.transition(sid, 'SUBMIT', expected=409)
    author.upload(sid, 'MANUSCRIPT', data=b'not a PDF', expected=400)
    manuscript = author.upload(sid, 'MANUSCRIPT')
    author.upload(sid, 'SUPPLEMENT', filename='data.csv', data=b'x,y\n1,2\n', media='text/plain')
    author.request('GET', '/api/submissions/' + sid + '/files')
    download = author.request('GET', '/api/files/' + manuscript + '/download')
    check('Manuscript download bytes match upload', download == sample_pdf())
    version = author.request('GET', '/api/submissions/' + sid)['version']
    author.request('PUT', '/api/submissions/' + sid + '/metadata', {'version': version, 'metadata': metadata}, expected=204)
    author.request('PUT', '/api/submissions/' + sid + '/metadata', {'version': version, 'metadata': metadata}, expected=409)
    author.transition(sid, 'SUBMIT')
    manager.transition(sid, 'RETURN_TO_AUTHOR')
    check('Return-to-author projection', author.request('GET', '/api/submissions/' + sid)['state'] == 'REVISIONS_REQUIRED')
    author.transition(sid, 'SUBMIT')
    clients['section'].request('GET', '/api/submissions/' + sid, expected=403)
    manager.request('PUT', '/api/submissions/' + sid + '/section-editor', {'userId': users['section']}, expected=204)
    clients['section'].request('GET', '/api/submissions/' + sid)
    clients['othersection'].request('GET', '/api/submissions/' + sid, expected=403)
    manager.request('PUT', '/api/submissions/' + sid + '/review-mode', {'mode': 'DOUBLE_BLIND'}, expected=204)
    phase('two peer-review rounds and anonymity')
    for round_no in (1, 2):
        blind = manager.upload(sid, 'BLIND_MANUSCRIPT')
        manager.request('POST', '/api/files/' + blind + '/approve-blind', expected=204)
        (clients['section'] if round_no == 1 else manager).transition(sid, 'SEND_TO_REVIEW' if round_no == 1 else 'START_REVIEW_ROUND')
        manager.request('PUT', '/api/submissions/' + sid + '/review-mode', {'mode': 'OPEN'}, expected=409)
        due = (datetime.now(timezone.utc) + timedelta(days=10)).isoformat()
        invite = {'reviewerId': users['reviewer'], 'dueAt': due, 'formId': form}
        rid = manager.request('POST', '/api/submissions/' + sid + '/reviews', invite, expected=201)['id']
        if round_no == 1:
            decline = manager.request('POST', '/api/submissions/' + sid + '/reviews', dict(invite, reviewerId=users['decliner']), expected=201)['id']
            clients['decliner'].request('POST', '/api/reviews/' + decline + '/response', {'accept': False}, expected=204)
            clients['decliner'].request('GET', '/api/files/' + blind + '/download', expected=403)
        reviewer = clients['reviewer']
        reviewer.request('GET', '/api/reviews')
        invitation = reviewer.request('GET', '/api/reviews/' + rid)
        check('Invitation exposes only abstract, not author/files/form', not any(k in invitation for k in ('authors', 'files', 'formSchema')))
        reviewer.request('GET', '/api/files/' + blind + '/download', expected=403)
        clients['decliner'].request('GET', '/api/reviews/' + rid, expected=403)
        reviewer.request('POST', '/api/reviews/' + rid + '/response', {'accept': True}, expected=204)
        reviewer.request('POST', '/api/reviews/' + rid + '/response', {'accept': True}, expected=409)
        view = reviewer.request('GET', '/api/reviews/' + rid)
        check('Accepted double-blind reviewer has files without authors', bool(view.get('files')) and 'authors' not in view)
        reviewer.request('GET', '/api/files/' + blind + '/download')
        reviewer.request('GET', '/api/files/' + manuscript + '/download', expected=403)
        annotation = reviewer.upload(sid, 'ANNOTATION', assignment=rid)
        author.request('GET', '/api/files/' + annotation + '/download', expected=403)
        feedback = {'recommendation': 'MINOR_REVISION' if round_no == 1 else 'ACCEPT', 'answers': {'score': 4}, 'authorComments': 'Public feedback', 'editorComments': 'PRIVATE-EDITOR-' + RUN}
        reviewer.request('POST', '/api/reviews/' + rid + '/evaluation', dict(feedback, answers={'score': 99}), expected=400)
        reviewer.request('POST', '/api/reviews/' + rid + '/evaluation', feedback, expected=204)
        reviewer.request('POST', '/api/reviews/' + rid + '/evaluation', feedback, expected=409)
        public_author = json.dumps(author.request('GET', '/api/submissions/' + sid))
        check('Author cannot see reviewer ID/name/private feedback', users['reviewer'] not in public_author and 'PRIVATE-EDITOR-' not in public_author and 'API reviewer' not in public_author)
        if round_no == 1:
            manager.transition(sid, 'REQUEST_REVISIONS')
            author.upload(sid, 'REVISION')
            author.transition(sid, 'SUBMIT_REVISION')
    manager.request('GET', '/api/submissions/' + sid + '/audit')
    phase('copyediting, production and proof approval')
    manager.transition(sid, 'ACCEPT')
    for name, role in [('copyeditor', 'COPYEDITOR'), ('layout', 'LAYOUT_EDITOR'), ('proofreader', 'PROOFREADER')]:
        manager.request('POST', '/api/submissions/' + sid + '/staff', {'userId': users[name], 'role': role}, expected=204)
    copyeditor = clients['copyeditor']
    version = copyeditor.request('GET', '/api/submissions/' + sid)['version']
    copyeditor.request('PUT', '/api/submissions/' + sid + '/metadata', {'version': version, 'metadata': metadata}, expected=204)
    copyeditor.request('POST', '/api/submissions/' + sid + '/discussions', {'visibility': 'AUTHOR_STAFF', 'body': 'Synthetic phrasing correction'}, expected=201)
    manager.request('POST', '/api/submissions/' + sid + '/discussions', {'visibility': 'EDITOR_ONLY', 'body': 'PRIVATE-DISCUSSION-' + RUN}, expected=201)
    author_discussions = author.request('GET', '/api/submissions/' + sid + '/discussions')
    check('Author discussion hides editor-only messages', 'PRIVATE-DISCUSSION-' not in json.dumps(author_discussions))
    author.request('POST', '/api/submissions/' + sid + '/discussions', {'visibility': 'EDITOR_ONLY', 'body': 'Forbidden'}, expected=403)
    copyeditor.transition(sid, 'COMPLETE_COPYEDITING')
    manager.transition(sid, 'START_PRODUCTION')
    clients['proofreader'].transition(sid, 'APPROVE_PROOFS', expected=409)
    author.upload(sid, 'GALLEY', 'PDF', expected=403)
    galley = clients['layout'].upload(sid, 'GALLEY', 'PDF')
    html = clients['layout'].upload(sid, 'GALLEY', 'HTML', filename='article.html', data=b'<!doctype html><html><body>Synthetic article</body></html>', media='text/html')
    clients['layout'].upload(sid, 'GALLEY', 'JATS_XML', filename='article.xml', data=b'<?xml version="1.0"?><article><body>Synthetic</body></article>', media='application/xml')
    manager.transition(sid, 'APPROVE_PROOFS', expected=403)
    clients['proofreader'].transition(sid, 'APPROVE_PROOFS')
    clients['layout'].upload(sid, 'GALLEY', 'PDF', expected=403)
    phase('issue publishing, covers, preservation and indexing')
    issue = manager.request('POST', '/api/issues', {'volume': 1, 'number': int(RUN[:6], 16) + 1, 'year': 2026, 'titles': {'en': 'API issue ' + RUN, 'ar': 'عدد'}}, expected=201)['id']
    manager.request('GET', '/api/issues')
    anonymous.request('GET', '/api/public/issues/' + issue, expected=404)
    manager.request('POST', '/api/issues/' + issue + '/publish', expected=409)
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aVp0AAAAASUVORK5CYII=')
    cover = manager.upload(filename='cover.png', data=png, media='image/png')
    manager.request('PUT', '/api/issues/' + issue + '/cover', {'coverId': cover}, expected=204)
    anonymous.request('GET', '/api/public/covers/' + cover, expected=404)
    manager.request('POST', '/api/issues/' + issue + '/articles', {'submissionId': sid, 'position': 1}, expected=204)
    version = manager.request('GET', '/api/submissions/' + sid)['version']
    manager.request('POST', '/api/submissions/' + sid + '/publish', {'version': version}, expected=409)
    manager.request('POST', '/api/issues/' + issue + '/publish', expected=204)
    manager.request('POST', '/api/issues/' + issue + '/publish', expected=409)
    anonymous.request('GET', '/api/public/issues')
    public_issue = anonymous.request('GET', '/api/public/issues/' + issue)
    check('Published issue includes article', any(a['id'] == sid for a in public_issue['articles']))
    check('Public cover exact bytes', anonymous.request('GET', '/api/public/covers/' + cover) == png)
    anonymous.request('GET', '/api/public/articles')
    public = anonymous.request('GET', '/api/public/articles/' + sid)
    check('Published article hides author emails and workflow/reviews', author_email not in json.dumps(public) and not any(k in public for k in ('state', 'reviews', 'ownerId')))
    check('Published PDF exact bytes', anonymous.request('GET', '/api/public/galleys/' + galley + '/download') == sample_pdf())
    anonymous.request('GET', '/api/public/galleys/' + html + '/download')
    citation = anonymous.request('GET', '/articles/' + sid)
    check('Citation HTML contains scholarly metadata', b'citation_title' in citation and b'citation_pdf_url' in citation)
    anonymous.request('GET', '/api/preservation/manifest')
    anonymous.request('GET', '/api/preservation/issues/' + issue + '/manifest')
    inventory = anonymous.request('GET', '/api/preservation/issues/' + issue + '/inventory')
    check('Preservation inventory contains 3 current galleys', len(inventory['files']) == 3)
    archive = anonymous.request('GET', '/api/preservation/issues/' + issue + '/package.zip')
    with zipfile.ZipFile(io.BytesIO(archive)) as bag:
        check('BagIt declaration', b'BagIt-Version: 1.0' in bag.read('bagit.txt'))
        valid = True
        for line in bag.read('manifest-sha256.txt').decode().splitlines():
            digest, path = line.split('  ', 1)
            valid &= hashlib.sha256(bag.read(path)).hexdigest() == digest
        check('Every BagIt manifest checksum matches actual bytes', valid)
    ns = {'o': 'http://www.openarchives.org/OAI/2.0/'}
    for verb in ('Identify', 'ListMetadataFormats', 'ListSets', 'GetRecord', 'ListRecords', 'ListIdentifiers'):
        params = {'verb': verb}
        if verb in ('GetRecord', 'ListRecords', 'ListIdentifiers'):
            params['metadataPrefix'] = 'oai_dc'
        if verb == 'GetRecord':
            params['identifier'] = 'oai:allam:' + sid
        if verb in ('ListRecords', 'ListIdentifiers'):
            params['set'] = section
        for method in ('GET', 'POST'):
            encoded = urllib.parse.urlencode(params)
            xml = anonymous.request(method, '/oai?' + encoded if method == 'GET' else '/oai', raw=encoded.encode() if method == 'POST' else None,
                                    content_type='application/x-www-form-urlencoded' if method == 'POST' else None, csrf=False)
            root = ET.fromstring(xml)
            check(f'OAI {method} {verb} well formed and no protocol error', root.find('o:error', ns) is None and root.find('o:' + verb, ns) is not None)
    for query, code in [('verb=Invalid', 'badVerb'), ('verb=GetRecord&metadataPrefix=oai_dc&identifier=absent', 'idDoesNotExist'),
                        ('verb=ListRecords&metadataPrefix=unknown', 'cannotDisseminateFormat'),
                        ('verb=ListRecords&resumptionToken=tampered', 'badResumptionToken'),
                        ('verb=Identify&verb=Identify', 'badArgument')]:
        root = ET.fromstring(anonymous.request('GET', '/oai?' + query))
        check('OAI error ' + code, root.find('o:error', ns).get('code') == code)
    phase('analytics, outbox and continuous publication')
    from_at = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    until_at = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
    usage_path = '/api/admin/usage?' + urllib.parse.urlencode({'from': from_at, 'until': until_at})
    before = manager.request('GET', usage_path)
    anonymous.request('GET', '/api/public/articles/' + sid, agent='Googlebot')
    after_bot = manager.request('GET', usage_path)
    check('Bot abstract request not counted', before == after_bot)
    human_agent = 'Mozilla/5.0 API verification ' + RUN
    anonymous.request('GET', '/api/public/articles/' + sid, agent=human_agent)
    anonymous.request('GET', '/api/public/articles/' + sid, agent=human_agent)
    anonymous.request('GET', '/api/public/galleys/' + galley + '/download', agent=human_agent)
    after_human = manager.request('GET', usage_path)
    values = {row['metric']: int(row['total']) for row in after_human if row['submission_id'] == sid}
    check('Human abstract deduplicated and galley tracked', values.get('ABSTRACT_VIEW') == 1 and values.get('GALLEY_DOWNLOAD') == 1)
    manager.request('GET', '/api/admin/usage?' + urllib.parse.urlencode({'from': until_at, 'until': from_at}), expected=400)
    events = manager.request('GET', '/api/admin/outbox?size=100')
    manager.request('POST', '/api/admin/outbox/' + events[0]['id'] + '/retry', expected=409,
                    label='Outbox refuses retry of non-DEAD event (DEAD success requires fault injection)')
    # Separate manuscripts verify desk rejection, single/open blindness and continuous publishing.
    for mode in ('SINGLE_BLIND', 'OPEN'):
        second = draft(' ' + mode)
        author.upload(second, 'MANUSCRIPT')
        author.transition(second, 'SUBMIT')
        manager.request('PUT', '/api/submissions/' + second + '/review-mode', {'mode': mode}, expected=204)
        blind = manager.upload(second, 'BLIND_MANUSCRIPT')
        manager.request('POST', '/api/files/' + blind + '/approve-blind', expected=204)
        manager.transition(second, 'SEND_TO_REVIEW')
        rid = manager.request('POST', '/api/submissions/' + second + '/reviews', {'reviewerId': users['reviewer'], 'dueAt': due, 'formId': form}, expected=201)['id']
        clients['reviewer'].request('POST', '/api/reviews/' + rid + '/response', {'accept': True}, expected=204)
        check(mode + ' accepted reviewer sees authors', 'authors' in clients['reviewer'].request('GET', '/api/reviews/' + rid))
        clients['reviewer'].request('POST', '/api/reviews/' + rid + '/evaluation', {'recommendation': 'ACCEPT', 'answers': {'score': 5}}, expected=204)
        authors_view = author.request('GET', '/api/submissions/' + second)
        check(mode + ' author reviewer identity policy', ('reviewer' in authors_view['reviews'][0]) == (mode == 'OPEN'))
        if mode == 'SINGLE_BLIND':
            manager.transition(second, 'REJECT')
            check('Rejected author projection', author.request('GET', '/api/submissions/' + second)['state'] == 'REJECTED')
            continue
        manager.transition(second, 'ACCEPT')
        for name, role in [('copyeditor', 'COPYEDITOR'), ('layout', 'LAYOUT_EDITOR'), ('proofreader', 'PROOFREADER')]:
            manager.request('POST', '/api/submissions/' + second + '/staff', {'userId': users[name], 'role': role}, expected=204)
        clients['copyeditor'].transition(second, 'COMPLETE_COPYEDITING')
        manager.transition(second, 'START_PRODUCTION')
        clients['layout'].upload(second, 'GALLEY', 'PDF')
        clients['proofreader'].transition(second, 'APPROVE_PROOFS')
        version = manager.request('GET', '/api/submissions/' + second)['version']
        manager.request('POST', '/api/submissions/' + second + '/publish', {'version': version}, expected=204)
        check('Continuous article publicly accessible', bool(anonymous.request('GET', '/api/public/articles/' + second)['publishedAt']))
    rejected = draft(' desk reject')
    author.upload(rejected, 'MANUSCRIPT')
    author.transition(rejected, 'SUBMIT')
    manager.transition(rejected, 'DESK_REJECT')
    author.transition(rejected, 'SUBMIT', expected=409)
    manager.request('POST', '/api/submissions/' + rejected + '/discussions', {'visibility': 'AUTHOR_STAFF', 'body': 'Closed'}, expected=409)
    phase('email delivery and logout')
    mail_url = os.getenv('MAILPIT_URL')
    if mail_url:
        # Read only the local Mailpit test inbox; no external messages are sent.
        for attempt in range(150):
            with urllib.request.urlopen(mail_url + '/api/v1/messages', timeout=10) as response:
                inbox = json.load(response)
            if RUN in json.dumps(inbox):
                break
            if attempt and attempt % 15 == 0:
                print('WAIT SMTP: queued notifications are processing', flush=True)
            time.sleep(2)
        check("Local SMTP captured this run's workflow notifications", RUN in json.dumps(inbox))
    author.request('POST', '/api/auth/logout', expected=204)
    author.request('GET', '/api/me', expected=401)
    anonymous.request('GET', '/api/public/articles?page=-1', expected=400)
    anonymous.request('GET', '/api/preservation/issues/missing/inventory', expected=404)
    check('Every documented operation exercised', COVERED == OPERATIONS)


def finish(fatal=None):
    report = {'run': RUN, 'baseUrl': BASE, 'completedAt': datetime.now(timezone.utc).isoformat(),
              'checks': len(RESULTS), 'passed': sum(r['passed'] for r in RESULTS),
              'failed': sum(not r['passed'] for r in RESULTS), 'documentedOperations': len(OPERATIONS),
              'exercisedOperations': len(COVERED), 'successfulOperations': len(SUCCESS),
              'unexercised': [m + ' ' + p for m, p in sorted(OPERATIONS - COVERED)],
              'withoutSuccessResponse': [m + ' ' + p for m, p in sorted(OPERATIONS - SUCCESS)],
              'limitations': ['Live ORCID OAuth success requires configured external credentials.',
                              'Outbox DEAD-event replay success requires isolated fault injection; rejection tested.',
                              'External DOI/preservation-network acceptance and certified COUNTER compliance are not established by these HTTP tests.'],
              'fatal': fatal, 'results': RESULTS}
    destination = Path(os.getenv('REPORT_DIR', '/tmp/api-test-report'))
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    summary = {k: v for k, v in report.items() if k != 'results'}
    (destination / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    rows = ''.join('<tr><td>' + html.escape(m) + '</td><td>' + html.escape(p) + '</td><td>' +
                   ('SUCCESS' if (m, p) in SUCCESS else 'GUARD TESTED / SUCCESS LIMITED') + '</td></tr>'
                   for m, p in sorted(OPERATIONS))
    failed_rows = ''.join('<li>' + html.escape(r['test']) + '</li>' for r in RESULTS if not r['passed'])
    page = '<!doctype html><html lang="en"><meta charset="utf-8"><title>Journal API test report</title>'
    page += '<style>body{font:16px system-ui;margin:40px;max-width:1100px}table{border-collapse:collapse;width:100%}td,th{text-align:left;border-bottom:1px solid #ddd;padding:10px}code{font-family:monospace}</style>'
    page += '<h1>Journal API test report</h1><p>Run ' + RUN + ' — ' + report['completedAt'] + '</p>'
    page += '<p><strong>' + str(report['passed']) + '/' + str(report['checks']) + ' checks passed. ' + str(len(COVERED)) + '/65 documented operations exercised.</strong></p>'
    page += '<p>' + str(len(SUCCESS)) + ' operations returned a successful response. Guards were tested for the remaining operations.</p>'
    page += '<p><a href="report.json">Detailed JSON report</a> · <a href="summary.json">Summary JSON</a></p>'
    page += '<h2>Failures</h2><ul>' + (failed_rows or '<li>None</li>') + '</ul>'
    page += '<h2>Limits</h2><ul>' + ''.join('<li>' + html.escape(x) + '</li>' for x in report['limitations']) + '</ul>'
    page += '<h2>Operation coverage</h2><table><thead><tr><th>Method</th><th>Path</th><th>Result</th></tr></thead><tbody>' + rows + '</tbody></table></html>'
    (destination / 'index.html').write_text(page)

    print('API_TEST_REPORT ' + json.dumps(summary, ensure_ascii=False), flush=True)
    print('FAILED_CHECKS ' + json.dumps([r for r in RESULTS if not r['passed']]), flush=True)
    port = os.getenv('REPORT_PORT')
    if port:
        os.chdir(destination)
        http.server.ThreadingHTTPServer(('0.0.0.0', int(port)), http.server.SimpleHTTPRequestHandler).serve_forever()
    return 1 if fatal or report['failed'] else 0


if __name__ == '__main__':
    fatal = None
    try:
        main()
    except Exception as e:
        fatal = type(e).__name__ + ': ' + str(e)
        traceback.print_exc()
    sys.exit(finish(fatal))
