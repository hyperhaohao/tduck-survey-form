#!/usr/bin/env python3
"""One-time repair for local user names created by the first Casdoor sync.

The first sync decoded psql output with the host locale and double-encoded
Chinese names. This script re-reads Casdoor display names as UTF-8 and updates
only the newly created local users (id >= 910007); existing accounts such as
the maintenance administrator are left unchanged.
"""
import json
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path('/data/apps/ruima-survey')
ORGANIZATION = 'ruima'


def run(args, cwd=None, input_data=None, check=True):
    result = subprocess.run(args, cwd=None if cwd is None else str(cwd), input=input_data, capture_output=True)
    if check and result.returncode:
        raise RuntimeError('CMD_FAILED ' + ' '.join(args) + ' :: ' + (result.stderr or b'').decode('utf-8', 'replace')[-400:])
    return result


def casdoor_users():
    info = json.loads(subprocess.check_output(['docker', 'inspect', 'poc-outline-db'], text=True))[0]
    env = dict(x.split('=', 1) for x in info['Config']['Env'] if '=' in x)
    user = env.get('POSTGRES_USER', 'postgres')
    sql = (
        "SELECT id,name,COALESCE(display_name,'') FROM \"user\" "
        "WHERE owner='%s' AND is_forbidden=false AND is_deleted=false" % ORGANIZATION
    )
    result = subprocess.run(
        ['docker', 'exec', 'poc-outline-db', 'psql', '-U', user, '-d', 'casdoor', '-At', '-F', '\t', '-c', sql],
        capture_output=True, check=True, env={**os.environ, 'PGCLIENTENCODING': 'UTF8'})
    rows = {}
    for line in result.stdout.decode('utf-8', 'replace').splitlines():
        parts = line.split('\t')
        if len(parts) >= 3:
            rows[parts[0]] = parts[2] or parts[1]
    return rows


def mysql_container():
    result = run(['docker', 'compose', 'ps', '-q', 'db'], cwd=ROOT)
    cid = result.stdout.decode('utf-8', 'replace').strip()
    if not cid:
        raise RuntimeError('MYSQL_CONTAINER_MISSING')
    return cid


def mysql_query(cid, sql):
    result = run(['docker', 'exec', cid, 'mysql', '--defaults-extra-file=/run/secrets/mysql-root.cnf',
                  '--default-character-set=utf8mb4', '-N', '-B', 'ruima_survey', '-e', sql])
    return result.stdout.decode('utf-8', 'replace').strip()


def esc(value):
    return value.replace('\\', '\\\\').replace("'", "''")


def main():
    cid = mysql_container()
    names = casdoor_users()
    rows = mysql_query(
        cid,
        'SELECT a.id,i.subject FROM ac_user a JOIN rm_demand_identity i ON i.user_id=a.id WHERE a.id>=910007').splitlines()
    statements = ['START TRANSACTION;']
    updated = 0
    for line in rows:
        parts = line.split('\t')
        if len(parts) < 2:
            continue
        uid, subject = parts[0], parts[1]
        name = (names.get(subject) or '员工')[:32]
        statements.append("UPDATE ac_user SET name='%s',update_time=CURRENT_TIMESTAMP WHERE id=%s;" % (esc(name), uid))
        updated += 1
    statements.append('COMMIT;')
    path = pathlib.Path('/tmp/ruima-user-name-repair-%d.sql' % os.getpid())
    path.write_text('\n'.join(statements), encoding='utf-8')
    path.chmod(0o600)
    try:
        run(['docker', 'exec', '-i', cid, 'mysql', '--defaults-extra-file=/run/secrets/mysql-root.cnf',
             '--default-character-set=utf8mb4', 'ruima_survey'], input_data=path.read_bytes())
    finally:
        path.unlink(missing_ok=True)
    samples = mysql_query(cid, 'SELECT id,HEX(name) FROM ac_user WHERE id IN (910007,910008) ORDER BY id')
    print(json.dumps({'updated': updated, 'samplesHex': samples.splitlines()}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'error': str(error)[-500:]}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
