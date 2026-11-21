"""Expand only a running, owned disposable fixture for device/browser acceptance."""
import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import re
import subprocess
from dotenv import dotenv_values
import pymysql
from pymongo import MongoClient

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]

def run(args):
    file=ROOT/'target/recommendation-browser-fixture.json'
    fixture=json.loads(file.read_text())
    assert fixture['fixtureOnly'] and re.fullmatch('moodify_recommendation_test_[a-f0-9]{12}',fixture['database'])
    config=dotenv_values(HERE/'.env.smoke.example')
    sql=pymysql.connect(host='127.0.0.1',port=13316,user='rec_fixture',password=config['DATABASE_PASSWORD'],database=fixture['database'],cursorclass=pymysql.cursors.DictCursor)
    with sql.cursor() as c:c.execute('SELECT @@server_id id');assert c.fetchone()['id']==773313
    client=MongoClient('mongodb://127.0.0.1:13318',tz_aware=True);mongo=client[fixture['database']]
    for i in range(414):
        key='bench-'+str(i)
        mongo.tracks.replace_one({'_id':key},{'_id':key,'spotify_id':'external-'+key,'name':'Fixture '+key,
            'artist_name':'Fixture Artist '+str(i%40),'artist_spotify_id':'fixture-artist-'+str(i%40),'genres':['pop'],
            'duration_ms':240000,'status':'published','visibility':'public','moderation_status':'approved',
            'audio_features':{'energy':.2+(i%10)*.06,'valence':.25+(i%10)*.05,'danceability':.5,'acousticness':.5,'bpm':110.}},upsert=True)
        mongo.artists.update_one({'_id':'fixture-artist-'+str(i%40)}, {'$set':{'name':'Fixture Artist '+str(i%40),'spotify_id':'fixture-artist-'+str(i%40)}},upsert=True)
    mongo.tracks.update_many({}, {'$set':{'status':'published','visibility':'public','moderation_status':'approved','local_path':args.audio_url,'duration_ms':args.duration_ms}})
    mongo.recommendation_state.delete_many({})
    # Generate fixture password with the exact BCrypt implementation used by this backend.
    jars=sorted((Path.home()/'.m2/repository/org/springframework/security/spring-security-crypto').glob('*/*.jar'))
    assert jars
    command='System.out.println(org.springframework.security.crypto.bcrypt.BCrypt.hashpw("RecTest-v13-Only!",org.springframework.security.crypto.bcrypt.BCrypt.gensalt()));\n/exit\n'
    java=Path('C:/Program Files/Java/jdk-26/bin/jshell.exe')
    result=subprocess.run([str(java),'--class-path',str(jars[-1])],input=command,text=True,capture_output=True,timeout=30,check=True)
    password=re.search(r'\$2a\$\d\d\$[A-Za-z0-9./]{53}',result.stdout).group()
    with sql.cursor() as c:
        for track in mongo.tracks.find({}, {'_id':1}):
            c.execute("INSERT INTO song_licenses(track_id,license_type,copyright_owner,status,issue_date,expiry_date) VALUES (%s,'TEST','Isolated acceptance fixture','ACTIVE',%s,%s)",
                (str(track['_id']),(datetime.now()-timedelta(days=1)).date(),(datetime.now()+timedelta(days=30)).date()))
        c.execute('UPDATE users SET password=%s WHERE id=1',(password,))
    sql.commit();sql.close();client.close()
    fixture['catalogCount']=426;file.write_text(json.dumps(fixture))
    print('Prepared isolated 426-track acceptance fixture; fixture login rec_test_1 / RecTest-v13-Only!')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--audio-url',required=True);p.add_argument('--duration-ms',type=int,required=True);run(p.parse_args())
