"""Explicitly invoked catalog cutover. Uses field updates; journals before-images.

The default mode is read-only. The caller must provide a fresh production backup,
verified media, and an isolated database for rehearsal. Never creates licenses or
pretends that an internal MongoDB id is a Spotify id.
"""
import argparse
import hashlib
import json
import re
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from bson import ObjectId, json_util
from pymongo import MongoClient, UpdateOne

BATCH='moodify-catalog-20261103-v1'
PREFIX='data/audio/moodify-import-20261103'
METADATA_FIELDS=('name','artist_name','artist_spotify_id','featured_artists','album_spotify_id',
                 'album_name','image_url','release_date','track_number','duration_ms',
                 'duration_formatted','explicit','genres','genres_raw','spotify_url')


def normalize(value):
    value=unicodedata.normalize('NFKD',(value or '').casefold().replace('đ','d'))
    return ' '.join(re.sub(r'[^a-z0-9]+',' ',''.join(c for c in value if not unicodedata.combining(c))).split())


def make_track(row, quality):
    h=row['source_sha256']; meta=dict(row.get('proposed_metadata') or {})
    duration=quality.get('duration_seconds')
    if not isinstance(duration,(int,float)) or duration<=0:raise ValueError('Missing measured duration')
    duration_ms=round(duration*1000)
    fields={key:meta[key] for key in METADATA_FIELDS if key in meta and meta[key] is not None}
    fields.setdefault('name',row['source_title'])
    fields.setdefault('artist_name',row['source_artists'])
    fields['duration_ms']=duration_ms
    fields['duration_formatted']=f'{duration_ms//60000}:{(duration_ms//1000)%60:02d}'
    fields['local_path']=f'{PREFIX}/{h[:2]}/{h}.mp3'
    fields['download_status']='completed'
    fields['audio_hash']=h
    fields['catalog_migration_batch']=BATCH
    fields['catalog_source_spotify_id']=row.get('source_spotify_id')
    fields['catalog_identity_evidence']=row.get('metadata_binding')
    fields['catalog_rights_evidence_status']='unverified'
    spotify=row.get('spotify_id_verified_by_metadata')
    if spotify:fields['spotify_id']=spotify
    return fields


def build_actions(db, plan, quality):
    rows=plan['tracks']
    if len(rows)!=400:raise ValueError('Expected 400 source files')
    primary=[x for x in rows if x['production_mapping']!='duplicate_alternative']
    if len(primary)!=399:raise ValueError('Expected exactly one documented duplicate alternative')
    current=list(db.tracks.find({}));by_id={str(d['_id']):d for d in current}
    if len(current)!=plan['summary']['production_snapshot_track_count']:
        raise ValueError('Production track count changed since reviewed snapshot')
    matched=[];inserts=[];seen_spotify=set();seen_id=set();artists={};albums={}
    for row in primary:
        h=row['source_sha256'];q=quality.get(h)
        if not q or q.get('hash_ok') is not True:raise ValueError('Missing integrity evidence '+h)
        fields=make_track(row,q);sp=fields.get('spotify_id')
        if sp:
            if sp in seen_spotify:raise ValueError('Duplicate source Spotify id')
            seen_spotify.add(sp)
        old_id=row.get('production_existing_track_id')
        if old_id:
            old=by_id.get(old_id)
            if not old or old.get('spotify_id')!=sp or normalize(old.get('name'))!=normalize(row['source_title']) or normalize(row['source_artists'].split(',')[0]) not in normalize(old.get('artist_name')):
                raise ValueError('Existing track no longer matches reviewed identity')
            matched.append((old,fields))
            entity_track_id=sp or str(old['_id'])
        else:
            if sp and any(d.get('spotify_id')==sp for d in current):raise ValueError('Unmapped Spotify id already exists')
            internal=ObjectId(hashlib.sha256((BATCH+':'+h).encode()).hexdigest()[:24])
            if str(internal) in by_id or str(internal) in seen_id:raise ValueError('Internal MongoDB ID conflict')
            seen_id.add(str(internal));inserts.append({'_id':internal,**fields,'status':'draft','visibility':'private','created_at':datetime.now(timezone.utc)})
            entity_track_id=sp or str(internal)
        artist_id=fields.get('artist_spotify_id')
        if artist_id:
            existing=artists.setdefault(artist_id,{'spotify_id':artist_id,'name':fields.get('artist_name')})
            if existing['name']!=fields.get('artist_name'):raise ValueError('Conflicting artist metadata')
        album_id=fields.get('album_spotify_id')
        if album_id:
            album=albums.setdefault(album_id,{'spotify_id':album_id,'name':fields.get('album_name'),
                'artist_name':fields.get('artist_name'),'artist_spotify_id':artist_id,
                'image_url':fields.get('image_url'),'release_date':fields.get('release_date'),'track_ids':[]})
            if album['name']!=fields.get('album_name'):raise ValueError('Conflicting album metadata')
            if entity_track_id not in album['track_ids']:album['track_ids'].append(entity_track_id)
    if len(matched)!=11 or len(inserts)!=388:raise ValueError('Unexpected matched/new counts')
    matched_ids={d['_id'] for d,_ in matched}
    archive=[d for d in current if d['_id'] not in matched_ids]
    return current,matched,inserts,archive,artists,albums


def apply(db, current, matched, inserts, archive, artists, albums, journal_path):
    if journal_path.exists():raise ValueError('Journal exists; choose a new path')
    journal_path.parent.mkdir(parents=True,exist_ok=True)
    old_artists={k:db.artists.find_one({'spotify_id':k}) for k in artists}
    old_albums={k:db.albums.find_one({'spotify_id':k}) for k in albums}
    for old in old_albums.values():
        if old and old.get('track_ids') is not None and not isinstance(old['track_ids'],list):
            raise ValueError('Existing album track_ids is not a list')
    journal={'batch':BATCH,'created_utc':datetime.now(timezone.utc),'old_tracks':current,
             'old_artists':old_artists,'old_albums':old_albums,'inserted_ids':[d['_id'] for d in inserts]}
    journal_path.write_text(json_util.dumps(journal,indent=2),encoding='utf-8')
    written=[];changed=[];changed_artists=[];changed_albums=[]
    try:
        for doc in inserts:
            db.tracks.insert_one(doc);written.append(doc['_id'])
        for identifier,artist in artists.items():
            old=old_artists[identifier]
            update={'$set':{'name':artist['name']},'$setOnInsert':{'spotify_id':identifier,'created_at':datetime.now(timezone.utc),'catalog_migration_batch':BATCH}}
            db.artists.update_one({'spotify_id':identifier},update,upsert=True)
            changed_artists.append((identifier,old))
        for identifier,album in albums.items():
            old=old_albums[identifier]
            fields={k:v for k,v in album.items() if k not in ('spotify_id','track_ids') and v is not None}
            new_ids=[x for x in album['track_ids'] if not old or x not in (old.get('track_ids') or [])]
            update={'$set':fields,'$setOnInsert':{'spotify_id':identifier,'created_at':datetime.now(timezone.utc),'catalog_migration_batch':BATCH},
                    '$addToSet':{'track_ids':{'$each':album['track_ids']}}}
            db.albums.update_one({'spotify_id':identifier},update,upsert=True)
            changed_albums.append((identifier,old,fields,new_ids))
        for old,fields in matched:
            # Preserve unrelated fields, including all play, likes, and user statistics.
            set_fields={**fields,'status':'published','visibility':'public'}
            unset={k:'' for k in METADATA_FIELDS if k not in fields}
            unset.update({'moderation_status':'','moderation_score':''})
            result=db.tracks.update_one({'_id':old['_id'],'spotify_id':old['spotify_id'],
                'name':old.get('name'),'artist_name':old.get('artist_name'),'local_path':old.get('local_path')},
                {'$set':set_fields,'$unset':unset})
            if result.matched_count!=1:raise RuntimeError('Matched track changed during cutover')
            changed.append((old,set(set_fields)|set(unset)))
        for old in archive:
            result=db.tracks.update_one({'_id':old['_id'],'name':old.get('name'),'local_path':old.get('local_path'),
                'status':old.get('status'),'visibility':old.get('visibility')},
                {'$set':{'status':'archived','visibility':'private','catalog_migration_archived_by':BATCH}})
            if result.matched_count!=1:raise RuntimeError('Old track changed during cutover')
            changed.append((old,{'status','visibility','catalog_migration_archived_by'}))
        result=db.tracks.update_many({'_id':{'$in':written},'catalog_migration_batch':BATCH,'status':'draft'},
                                     {'$set':{'status':'published','visibility':'public'}})
        if result.matched_count!=len(written):raise RuntimeError('Could not publish every staged track')
    except BaseException:
        for old,keys in reversed(changed):
            restore={k:old[k] for k in keys if k in old};unset={k:'' for k in keys if k not in old}
            update={}
            if restore:update['$set']=restore
            if unset:update['$unset']=unset
            if update:db.tracks.update_one({'_id':old['_id']},update)
        if written:db.tracks.delete_many({'_id':{'$in':written},'catalog_migration_batch':BATCH})
        for identifier,old,fields,new_ids in reversed(changed_albums):
            if not old:
                db.albums.delete_one({'spotify_id':identifier,'catalog_migration_batch':BATCH})
                continue
            restored={k:old[k] for k in fields if k in old};missing={k:'' for k in fields if k not in old};update={}
            if restored:update['$set']=restored
            if missing:update['$unset']=missing
            if new_ids:update['$pull']={'track_ids':{'$in':new_ids}}
            if update:db.albums.update_one({'spotify_id':identifier},update)
        for identifier,old in reversed(changed_artists):
            if old:
                db.artists.update_one({'spotify_id':identifier},{'$set':{'name':old.get('name')}})
            else:db.artists.delete_one({'spotify_id':identifier,'catalog_migration_batch':BATCH})
        raise
    return {'existing_updated':len(matched),'old_archived':len(archive),'new_tracks':len(written),
            'artists_linked':len(artists),'albums_linked':len(albums)}


def main():
    p=argparse.ArgumentParser();p.add_argument('--mongo-uri',required=True);p.add_argument('--database',required=True)
    p.add_argument('--plan',required=True,type=Path);p.add_argument('--quality',required=True,type=Path)
    p.add_argument('--journal',required=True,type=Path);p.add_argument('--apply',action='store_true')
    a=p.parse_args()
    if not a.mongo_uri.startswith(('mongodb://127.0.0.1:','mongodb://localhost:')):raise ValueError('Use a loopback connection or SSH tunnel')
    plan=json.loads(a.plan.read_text(encoding='utf-8'));quality={x['sha256']:x for x in json.loads(a.quality.read_text(encoding='utf-8'))}
    client=MongoClient(a.mongo_uri,serverSelectionTimeoutMS=5000);db=client[a.database]
    actions=build_actions(db,plan,quality);print(json.dumps({'current':len(actions[0]),'matched':len(actions[1]),'new':len(actions[2]),'archive':len(actions[3]),'mode':'apply' if a.apply else 'dry_run'}),flush=True)
    if a.apply:print(json.dumps(apply(db,*actions,a.journal)),flush=True)

if __name__=='__main__':main()
