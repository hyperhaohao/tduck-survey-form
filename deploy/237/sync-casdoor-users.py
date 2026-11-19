#!/usr/bin/env python3
"""Create local accounts and OIDC identity mappings for all active Casdoor
users in the ruima organization. Idempotent; intended for root cron on 237.

It never prints user credentials. Existing local users/identities are preserved;
new users are ordinary accounts (no admin or collection-manager grants).
"""
import hashlib
import json
import os
import pathlib
import secrets
import subprocess
import sys

ROOT = pathlib.Path('/data/apps/ruima-survey')
ISSUER = 'http://192.168.1.237:13004'
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
        "SELECT id,name,COALESCE(display_name,''),COALESCE(email,''),COALESCE(avatar,'') "
        "FROM \"user\" WHERE owner='%s' AND is_forbidden=false AND is_deleted=false ORDER BY name" % ORGANIZATION
    )
    result = subprocess.run(
        ['docker', 'exec', 'poc-outline-db', 'psql', '-U', user, '-d', 'casdoor', '-At', '-F', '\t', '-c', sql],
        capture_output=True, check=True, env={**os.environ, 'PGCLIENTENCODING': 'UTF8'})
    rows = []
    for line in result.stdout.decode('utf-8', 'replace').splitlines():
        parts = line.split('\t')
        if len(parts) >= 5:
            rows.append({'id': parts[0], 'name': parts[1], 'display': parts[2], 'email': parts[3], 'avatar': parts[4]})
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


def identity_hash(subject):
    return hashlib.sha256((ISSUER + '\n' + subject).encode('utf-8')).hexdigest()


def main():
    cid = mysql_container()
    users = casdoor_users()
    mapped = {}
    for line in mysql_query(cid, 'SELECT user_id,identity_hash FROM rm_demand_identity').splitlines():
        parts = line.split('\t')
        if len(parts) >= 2:
            mapped[parts[1]] = parts[0]
    mapped_user_ids = set(mapped.values())
    local_rows = mysql_query(cid, 'SELECT id,name,COALESCE(email,\'\') FROM ac_user WHERE deleted=0').splitlines()
    local_by_email = {}
    local_by_name = {}
    for line in local_rows:
        parts = line.split('\t')
        if len(parts) < 3:
            continue
        uid, name, email = parts[0], parts[1], parts[2]
        if uid in mapped_user_ids:
            continue
        if email:
            local_by_email[email] = uid
        local_by_name.setdefault(name, []).append(uid)

    statements = ['START TRANSACTION;']
    created = 0
    linked = 0
    skipped = 0
    for user in users:
        subject = user['id']
        h = identity_hash(subject)
        if h in mapped:
            skipped += 1
            continue
        local_id = None
        if user['email'] and user['email'] in local_by_email:
            local_id = local_by_email[user['email']]
        elif user['display'] and len(local_by_name.get(user['display'], [])) == 1:
            local_id = local_by_name[user['display']][0]
        if local_id:
            statements.append(
                "INSERT INTO rm_demand_identity(identity_hash,issuer,subject,user_id) VALUES ('%s','%s','%s',%s);"
                % (h, esc(ISSUER), esc(subject), local_id))
            linked += 1
            continue
        name = (user['display'] or user['name'] or '员工')[:32]
        email = user['email'][:100] if user['email'] else None
        avatar = (user['avatar'] or '')[:256]
        password = hashlib.sha256(secrets.token_bytes(32)).hexdigest()
        email_sql = 'NULL' if email is None else "'%s'" % esc(email)
        statements.append(
            "INSERT INTO ac_user(name,avatar,gender,email,phone_number,password,reg_channel,password_type,need_change_password,deleted) "
            "VALUES ('%s','%s',0,%s,NULL,'%s','5',0,0,0);"
            % (esc(name), esc(avatar), email_sql, password))
        statements.append('SET @uid=LAST_INSERT_ID();')
        statements.append(
            "INSERT INTO rm_demand_identity(identity_hash,issuer,subject,user_id) VALUES ('%s','%s','%s',@uid);"
            % (h, esc(ISSUER), esc(subject)))
        created += 1
    statements.append('COMMIT;')
    sql_path = pathlib.Path('/tmp/ruima-user-sync-%d.sql' % os.getpid())
    sql_path.write_text('\n'.join(statements), encoding='utf-8')
    sql_path.chmod(0o600)
    try:
        result = run(['docker', 'exec', '-i', cid, 'mysql', '--defaults-extra-file=/run/secrets/mysql-root.cnf',
                      'ruima_survey'], input_data=sql_path.read_bytes())
    finally:
        sql_path.unlink(missing_ok=True)
    total = int(mysql_query(cid, 'SELECT COUNT(*) FROM ac_user WHERE deleted=0') or 0)
    identities = int(mysql_query(cid, 'SELECT COUNT(*) FROM rm_demand_identity') or 0)
    print(json.dumps({'casdoorActive': len(users), 'created': created, 'linked': linked, 'skipped': skipped,
                      'localActive': total, 'identityMappings': identities}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'error': str(error)[-500:]}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
