"""Link imported recordings using saved recording identity and live provider artist IDs.

Never resolves an identity from a display name alone. Default is a reviewable plan.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen
from urllib.parse import urlencode
import unicodedata
from bson import ObjectId
from pymongo import MongoClient
from catalog_maintenance import journal

def normalized(value):
    return unicodedata.normalize('NFC',value).casefold().strip()

def run(args):
    args.output.mkdir(parents=True,exist_ok=True)
    client=MongoClient(args.mongo_uri,tz_aware=True,serverSelectionTimeoutMS=8000)
    mongo=client[args.database]
    evidence=[]
    for file in args.evidence:
        saved=json.loads(file.read_text(encoding='utf-8'))
        for t in saved['tracks']:
            match=t.get('best_catalog_match') or {}
            if t.get('catalog_status')=='title_artist_duration_match' and match.get('artistId'):
                evidence.append((t,match,str(file),hashlib.sha256(file.read_bytes()).hexdigest()))
    provider_file=args.output/'apple-artists.json'
    ids=sorted({str(m['artistId']) for _,m,_,_ in evidence})
    if not provider_file.exists():
        with urlopen('https://itunes.apple.com/lookup?'+urlencode({'id':','.join(ids)}),timeout=30) as r:providers=json.load(r)
        journal(provider_file,providers)
    providers={str(a['artistId']):a for a in json.loads(provider_file.read_text(encoding='utf-8'))['results'] if a.get('wrapperType')=='artist'}
    plan=[]
    for recording,match,file,digest in evidence:
        artist=providers.get(str(match['artistId']))
        if not artist:continue
        # Provider resolves its immutable artist ID; saved source independently credits that performer.
        credits={normalized(name) for name in recording.get('source_artists',[])}
        if normalized(artist.get('artistName','')) not in credits:continue
        candidates=list(mongo.tracks.find({'catalog_source.provider':'nhaccuatui','catalog_source.id':recording['source_id'],
            'catalog_source.apple_track_id':match['trackId'],'status':'published','visibility':'public'}))
        if len(candidates)!=1:continue
        track=candidates[0]
        artist_id=ObjectId(hashlib.sha256(('apple-artist:'+str(match['artistId'])).encode()).hexdigest()[:24])
        profiles=list(mongo.artists.find({'provider_ids.apple_music':str(match['artistId'])}))
        if len(profiles)>1:raise ValueError('ambiguous_provider_artist')
        if profiles:artist_id=profiles[0]['_id']
        before_artist=mongo.artists.find_one({'_id':artist_id})
        if before_artist and (before_artist.get('provider_ids') or {}).get('apple_music')!=str(match['artistId']):raise ValueError('artist_identity_collision')
        proposed=sorted(set(track.get('artist_ids') or [])|{str(artist_id)})
        if proposed==track.get('artist_ids'):continue
        row={'track_id':str(track['_id']),'audio_hash':track.get('audio_hash'),'artist_id':str(artist_id),
            'artist_name':artist['artistName'],'provider_artist_id':str(match['artistId']),
            'evidence_file':file,'evidence_sha256':digest,'provider_url':artist.get('artistLinkUrl'),
            'recording_url':recording['source_url'],'artist_ids':proposed}
        plan.append(row)
        if args.apply:
            attempt=args.output/(str(track['_id'])+'-before.json')
            if attempt.exists():
                from bson import json_util
                previous=json_util.loads(attempt.read_text(encoding='utf-8'))
                if not args.resume or any(previous['before_track'].get(k)!=track.get(k) for k in ['audio_hash','artist_ids','catalog_source']):
                    raise ValueError('Existing journal requires explicit recovery review')
            else:journal(attempt,{'before_track':track,'before_artist':before_artist,'proposed':row})
            if before_artist is None:
                mongo.artists.insert_one({'_id':artist_id,'name':artist['artistName'],'provider_ids':{'apple_music':str(match['artistId'])},
                    'identity_provenance':{'recording_source':recording['source_url'],'provider_url':artist.get('artistLinkUrl'),
                    'evidence_sha256':digest,'method':'verified_recording_and_provider_artist_id'},'created_at':datetime.now(timezone.utc)})
            result=mongo.tracks.update_one({'_id':track['_id'],'audio_hash':track.get('audio_hash'),'artist_ids':track.get('artist_ids')},
                {'$set':{'artist_ids':proposed,'artist_identity_provenance':{'method':'provider_artist_id_and_verified_recording',
                    'apple_artist_id':str(match['artistId']),'source_id':recording['source_id'],'evidence_sha256':digest}}})
            if result.modified_count!=1:raise ValueError('artist_link_compare_and_set_conflict')
            after=mongo.tracks.find_one({'_id':track['_id']})
            ignored={'artist_ids','artist_identity_provenance'}
            if {k:v for k,v in track.items() if k not in ignored}!={k:v for k,v in after.items() if k not in ignored}:raise ValueError('unrelated_fields_changed')
            journal(args.output/(str(track['_id'])+'-verified.json'),{'verified':True,'artist_ids':proposed,'audio_hash':after.get('audio_hash')})
    (args.output/'link-plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'planned_or_linked':len(plan),'provider_artists':len({r['artist_id'] for r in plan}),'applied':args.apply}))
    client.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--mongo-uri',required=True);p.add_argument('--database',default='music_streaming')
    p.add_argument('--evidence',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--apply',action='store_true');p.add_argument('--resume',action='store_true')
    run(p.parse_args())
