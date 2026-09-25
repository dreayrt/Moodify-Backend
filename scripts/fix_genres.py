import sys
import pymongo

sys.stdout.reconfigure(encoding='utf-8')
client = pymongo.MongoClient('mongodb://127.0.0.1:27017')
db = client['music_streaming']
col = db['tracks']

updated_count = 0
for doc in col.find({}):
    raw_genres = doc.get('genres_raw') or []
    current_genres = doc.get('genres') or []
    name = (doc.get('name') or '').lower()
    artist = (doc.get('artist_name') or '').lower()
    
    new_genre_label = None
    genre_code = 'v-pop'
    
    # Check title / artist first for strong signals
    if any(k in name for k in ['remix', 'dj ', 'wrc', 'drum', 'edm', 'vinahouse']):
        new_genre_label = 'EDM / Remix'
        genre_code = 'edm'
    elif any(k in artist or k in name for k in ['đen', 'b ray', 'binz', 'pháp kiều', 'coldzy', 'bigdaddy', 'hieuthuhai', 'dick', 'rap', 'hurrykng']):
        new_genre_label = 'Rap / Hip-Hop'
        genre_code = 'rap'
    elif any(k in artist or k in name for k in ['ngọt', 'the flob', 'indiek', 'lucidrari', 'ronboogz', 'yedira', 'ashen', 't.r.i', 'cheyenne', 'vẫn thế', 'trong bao nỗi buồn']):
        new_genre_label = 'Indie'
        genre_code = 'indie'
    elif any(k in artist for k in ['wren evans', 'kimmese', 'grey d']):
        new_genre_label = 'R&B / Soul'
        genre_code = 'r&b'
    elif any(k in artist or k in name for k in ['phạm hồng phước', 'hà nhi', 'duongg', 'buồn', 'mưa', 'nỗi buồn']):
        new_genre_label = 'Ballad'
        genre_code = 'ballad'
    else:
        # Check raw genres
        for r in raw_genres:
            if not r: continue
            rl = r.lower()
            if 'hiphop' in rl or 'rap' in rl:
                new_genre_label = 'Rap / Hip-Hop'
                genre_code = 'rap'
                break
            elif 'indie' in rl:
                new_genre_label = 'Indie'
                genre_code = 'indie'
                break
            elif 'r&b' in rl or 'rnb' in rl:
                new_genre_label = 'R&B / Soul'
                genre_code = 'r&b'
                break
            elif 'edm' in rl or 'dance' in rl or 'remix' in rl:
                new_genre_label = 'EDM / Remix'
                genre_code = 'edm'
                break
            elif 'ballad' in rl:
                new_genre_label = 'Ballad'
                genre_code = 'ballad'
                break
            elif 'vpop' in rl or 'pop' in rl:
                new_genre_label = 'V-Pop'
                genre_code = 'v-pop'
                break

    if not new_genre_label:
        new_genre_label = 'V-Pop'
        genre_code = 'v-pop'
        
    # Standardize MongoDB genre array: put the human-readable genre label FIRST so t.getGenres().get(0) in Spring Boot gets the real genre!
    new_genres_list = [new_genre_label, genre_code]
    for g in current_genres:
        if g and g.lower() != 'other' and g not in new_genres_list:
            new_genres_list.append(g)
            
    col.update_one({'_id': doc['_id']}, {'$set': {'genres': new_genres_list}})
    updated_count += 1

print(f'Successfully updated {updated_count} tracks in MongoDB music_streaming.tracks!')

# Verify sample
sample = list(col.find({}, {'name': 1, 'artist_name': 1, 'genres': 1}).limit(10))
for s in sample:
    print(f"Track: {s.get('name')} | Artist: {s.get('artist_name')} | Genres: {s.get('genres')}")
