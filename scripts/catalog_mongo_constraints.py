"""Install strict catalog shape/publication validators after repair, with no data rewriting."""
def install(db):
    track={'$and':[
      {'$jsonSchema':{'bsonType':'object','required':['name','status','visibility','artist_ids'],
       'properties':{'name':{'bsonType':'string','minLength':1},'status':{'bsonType':'string'},'visibility':{'enum':['public','private','unlisted']},
         'artist_ids':{'bsonType':'array','uniqueItems':True,'items':{'bsonType':'string','pattern':'^[0-9a-f]{24}$'}},
         'audio_hash':{'bsonType':['string','null'],'pattern':'^[0-9a-f]{64}$'},
         'has_synced_lyrics':{'bsonType':'bool'},'catalog_revision':{'bsonType':['int','long','double'],'minimum':0}}}},
      {'$or':[{'status':{'$ne':'published'}},{'$and':[
         {'moderation_status':'approved'}, {'local_path':{'$type':'string','$regex':'^(?:/?data/audio/|/uploads/audio/)'}},
         {'duration_ms':{'$type':['int','long','double'],'$gt':0}}, {'audio_hash':{'$type':'string','$regex':'^[0-9a-f]{64}$'}},
         {'artist_ids.0':{'$exists':True}}, {'is_tombstone':{'$ne':True}}, {'deletion_status':{'$nin':['PENDING','COMPLETE']}}
      ]}]}]}
    validators={'tracks':track,'albums':{'$jsonSchema':{'bsonType':'object','properties':{'track_ids':{'bsonType':'array','uniqueItems':True,'items':{'bsonType':'string'}}}}},
       'artists':{'$jsonSchema':{'bsonType':'object','required':['name'],'properties':{'name':{'bsonType':'string','minLength':1},'spotify_id':{'bsonType':['string','null']}}}},
       'playlists':{'$jsonSchema':{'bsonType':'object','properties':{'track_spotify_ids':{'bsonType':'array','uniqueItems':True,'items':{'bsonType':'string'}},'is_public':{'bsonType':'bool'}}}}}
    for name,validator in validators.items():
        invalid=list(db[name].find({'$nor':[validator]},{'_id':1}).limit(10))
        if invalid:raise ValueError(('Existing documents fail validator',name,[str(d['_id']) for d in invalid]))
        db.command({'collMod':name,'validator':validator,'validationLevel':'strict','validationAction':'error'})
    for name in ['tracks','artists']:
        equivalent=any(list(index['key'].items())==[('spotify_id',1)] and index.get('unique') and
            index.get('partialFilterExpression')=={'spotify_id':{'$type':'string'}} for index in db[name].list_indexes())
        if not equivalent:db[name].create_index('spotify_id',name='uq_integrity_spotify_string',unique=True,partialFilterExpression={'spotify_id':{'$type':'string'}})
    db.catalog_operations.create_index([('state',1),('created_at',1)],name='pending_catalog_operations')
    return {'collections':list(validators),'validationLevel':'strict','validationAction':'error','unique_provider_ids':True}
