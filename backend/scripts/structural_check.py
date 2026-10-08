#!/usr/bin/env python3
"""Dependency-free checks; deliberately not a replacement for javac or MySQL tests."""
from pathlib import Path
import json
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent

def balanced_java(path):
    text = path.read_text()
    stack = []
    state = 'code'
    index = 0
    line = 1
    while index < len(text):
        character = text[index]
        next_character = text[index + 1:index + 2]
        if character == '\n':
            line += 1
        if state in ('string', 'char'):
            if character == '\\':
                index += 2
                continue
            if character == ('"' if state == 'string' else "'"):
                state = 'code'
            elif character == '\n':
                raise AssertionError(f'{path}:{line}: newline in literal')
        elif state == 'line_comment':
            if character == '\n':
                state = 'code'
        elif state == 'block_comment':
            if character == '*' and next_character == '/':
                state = 'code'
                index += 2
                continue
        elif character == '/' and next_character in ('/', '*'):
            state = 'line_comment' if next_character == '/' else 'block_comment'
            index += 2
            continue
        elif character in ('"', "'"):
            state = 'string' if character == '"' else 'char'
        elif character in '({[':
            stack.append((character, line))
        elif character in ')}]':
            assert stack, f'{path}:{line}: unexpected delimiter'
            opening, _ = stack.pop()
            assert '({['.index(opening) == ')}]'.index(character), f'{path}:{line}: mismatched delimiter'
        index += 1
    assert not stack and state in ('code', 'line_comment'), f'{path}: unterminated construct'

java_files = list((ROOT / 'src').rglob('*.java'))
for java in java_files:
    balanced_java(java)
print(f'PASS: lexical delimiter/literal checks for {len(java_files)} Java files (no compilation)')
ET.parse(ROOT / 'pom.xml')
print('PASS: Maven POM is well-formed XML')
spec = json.loads((ROOT / 'docs/openapi.json').read_text())
assert spec['openapi'] == '3.0.3'
for path, operations in spec['paths'].items():
    for method, operation in operations.items():
        assert method in ('get', 'post', 'put', 'delete') and operation['responses']
        for parameter in re.findall(r'\{([^}]+)\}', path):
            assert any(p['name'] == parameter and p['in'] == 'path' and p['required'] for p in operation.get('parameters', []))

def references(value):
    if isinstance(value, dict):
        if '$ref' in value:
            assert value['$ref'].split('/')[-1] in spec['components']['schemas']
        for child in value.values():
            references(child)
    elif isinstance(value, list):
        for child in value:
            references(child)
references(spec)
print(f"PASS: OpenAPI JSON, path parameters and schema references ({sum(len(v) for v in spec['paths'].values())} operations)")
# SQLite accepts these type declarations; this validates DDL relationships and keys, not MySQL behavior.
connection = sqlite3.connect(':memory:')
connection.execute('PRAGMA foreign_keys=ON')
for migration in sorted((ROOT / 'src/main/resources/db/migration').glob('*.sql')):
    connection.executescript(migration.read_text())
tables = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")]
for table in tables:
    for foreign in connection.execute('PRAGMA foreign_key_list(' + table + ')'):
        assert foreign[2] in tables
        columns = [row[1] for row in connection.execute('PRAGMA table_info(' + foreign[2] + ')')]
        assert foreign[4] in columns
assert not list(connection.execute('PRAGMA foreign_key_check'))
print(f'PASS: DDL parses in SQLite and all foreign-key targets exist ({len(tables)} tables; not MySQL validation)')
connection.close()
compile((ROOT / 'scripts/smoke_workflow.py').read_text(), 'smoke_workflow.py', 'exec')
print('PASS: smoke client Python syntax')
