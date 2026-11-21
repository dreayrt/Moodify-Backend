"""Keep real Spotify IDs unique while allowing provider-neutral artist documents."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
from pymongo import MongoClient
from catalog_maintenance import journal

def run(args):
    client=MongoClient(args.mongo_uri,serverSelectionTimeoutMS=8000)
    artists=client[args.database].artists
    before=list(artists.list_indexes())
    args.output.mkdir(parents=True,exist_ok=True)
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    journal(args.output/(stamp+'-before.json'),{'indexes':before,'documents':artists.count_documents({}),'apply':args.apply})
    if args.apply:
        artists.create_index([('spotify_id',1)],name='spotify_id_verified_unique',unique=True,
            partialFilterExpression={'spotify_id':{'$type':'string'}})
        current=artists.index_information()['spotify_id_verified_unique']
        assert current['unique'] and current['partialFilterExpression']=={'spotify_id':{'$type':'string'}}
        for index in before:
            if (index['name']!='spotify_id_verified_unique' and list(index['key'].items())==[('spotify_id',1)]
                and index.get('unique') and not index.get('sparse') and not index.get('partialFilterExpression')):
                artists.drop_index(index['name'])
        journal(args.output/(stamp+'-verified.json'),{'indexes':list(artists.list_indexes()),'documents':artists.count_documents({})})
    print('Artist identity index '+('migrated and verified' if args.apply else 'reviewed; no database writes'))
    client.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--mongo-uri',required=True);p.add_argument('--database',default='music_streaming')
    p.add_argument('--output',type=Path,required=True);p.add_argument('--apply',action='store_true');run(p.parse_args())
