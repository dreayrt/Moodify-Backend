"""Read-only input for explicitly labelled demonstration licensing documents."""
from datetime import datetime,date,timezone
from decimal import Decimal
from pathlib import Path
import os,sys
sys.path.insert(0,os.getcwd())
import worker
from bson import json_util

sql,mongo,client=worker.connections(Path('/dev/null'))
try:
    with sql.cursor() as cursor:
        cursor.execute('START TRANSACTION READ ONLY')
        cursor.execute('SELECT id,track_id,license_type,copyright_owner,status,issue_date,expiry_date,document_songlicenses_url,distribution_contract_code,acquisition_cost_vnd FROM song_licenses')
        licenses=cursor.fetchall()
    for row in licenses:
        for key,value in row.items():
            if isinstance(value,date):row[key]=value.isoformat()
            elif isinstance(value,Decimal):row[key]=str(value)
    tracks=list(mongo.tracks.find({'status':'published','visibility':'public'}))
    print(json_util.dumps({'at':datetime.now(timezone.utc),'read_only':True,'tracks':tracks,'licenses':licenses},ensure_ascii=False))
finally:
    sql.rollback();sql.close();client.close()
