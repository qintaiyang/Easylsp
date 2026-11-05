#!/usr/bin/env python3
"""Apply production v2->v3 SQL to exported Room schema and preserve a text Skill."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / 'app/schemas/io.github.easylsp.app.data.EasylspDatabase'
def database(version):
    db = sqlite3.connect(':memory:')
    for entity in json.loads((schemas / f'{version}.json').read_text())['database']['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity['indices']:
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return db

actual = database(2)
actual.execute("INSERT INTO skills VALUES ('old-skill', 'Existing', 'Keep these instructions', 1)")
source = (root / 'app/src/main/java/io/github/easylsp/app/data/Database.kt').read_text()
migration = source.split('val MIGRATION_2_3 =', 1)[1].split('val MIGRATION_1_2 =', 1)[0]
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
assert len(statements) == 3
for statement in statements:
    actual.execute(statement)
expected = database(3)
assert actual.execute('PRAGMA table_info(skills)').fetchall() == expected.execute('PRAGMA table_info(skills)').fetchall()
assert actual.execute('SELECT * FROM skills').fetchone() == ('old-skill', 'Existing', 'Keep these instructions', 1, 'text', '', 120)
print('PASS: production v2→v3 migration matches Room v3 skills schema and preserves existing text Skill')
