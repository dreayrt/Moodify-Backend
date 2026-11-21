"""Windows fallback for Docker-less isolated test servers. Never uses application credentials."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
STATE = ROOT/'target/recommendation-fixture'
MYSQL = Path('C:/Program Files/MySQL/MySQL Server 8.0/bin')
MONGOD = Path('C:/Program Files/MongoDB/Server/8.2/bin/mongod.exe')

def start(resume=False):
    import pymysql
    from pymongo import MongoClient
    for port in (13316, 13318):
        with socket.socket() as s: s.bind(('127.0.0.1',port))
    STATE.mkdir(parents=True,exist_ok=True)
    data = STATE/'mysql'
    if data.exists() and not resume: raise RuntimeError('Existing fixture data retained; use resume explicitly')
    data.mkdir(exist_ok=True)
    if not resume:
      with (STATE/'mysql-initialize.log').open('wb') as log:
        subprocess.run([str(MYSQL/'mysqld.exe'),'--no-defaults','--initialize-insecure','--datadir='+str(data)],
            stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW,timeout=60,check=True)
    processes=[]
    mysql_log=(STATE/'mysql.log').open('ab')
    process=subprocess.Popen([str(MYSQL/'mysqld.exe'),'--no-defaults','--datadir='+str(data),'--port=13316',
        '--bind-address=127.0.0.1','--server-id=773313','--event-scheduler=OFF','--skip-log-bin','--mysqlx=0','--console'],
        stdout=mysql_log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    processes.append(process.pid)
    for _ in range(60):
        try:
            sql=pymysql.connect(host='127.0.0.1',port=13316,user='root',connect_timeout=1);break
        except pymysql.Error: time.sleep(.5)
    else: raise RuntimeError('Isolated MySQL failed to start')
    with sql.cursor() as cursor:
        cursor.execute('SELECT @@server_id');assert cursor.fetchone()[0]==773313
        if not resume:
            for statement in (HERE/'fixture-init.sql').read_text().split(';'):
                if statement.strip():cursor.execute(statement)
    sql.close()
    mongo_data=STATE/'mongo';mongo_data.mkdir(exist_ok=True)
    mongo_log=(STATE/'mongo-process.log').open('ab')
    process=subprocess.Popen([str(MONGOD),'--bind_ip','127.0.0.1','--port','13318','--dbpath',str(mongo_data),
        '--logpath',str(STATE/'mongo.log')],stdout=mongo_log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    processes.append(process.pid)
    (STATE/'processes.json').write_text(json.dumps(processes))
    client=MongoClient('mongodb://127.0.0.1:13318',serverSelectionTimeoutMS=1000)
    for _ in range(60):
        try:client.admin.command('ping');break
        except Exception:time.sleep(.5)
    else:raise RuntimeError('Isolated Mongo failed to start')
    client.close()
    print('Fixture ready on 127.0.0.1:13316/13318; SQL fixture-only grants; server_id=773313')

def stop():
    env=os.environ.copy();env.pop('MYSQL_PWD',None)
    subprocess.run([str(MYSQL/'mysqladmin.exe'),'--host=127.0.0.1','--port=13316','--user=root','shutdown'],env=env,capture_output=True,timeout=15,check=True)
    from pymongo import MongoClient
    client=MongoClient('mongodb://127.0.0.1:13318',serverSelectionTimeoutMS=1000)
    try:client.admin.command('shutdown')
    except Exception:pass  # shutdown closes the connection before acknowledging.
    finally:client.close()
    print('Isolated fixture stopped; data retained under target/recommendation-fixture')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['start','resume','stop']);args=parser.parse_args()
    stop() if args.action=='stop' else start(resume=args.action=='resume')
