"""Overlay a restored production snapshot onto a read-only catalog plan."""
import argparse
import collections
import csv
import json
from pathlib import Path


def build_plan(local_plan, impact):
    if impact.get('success') is not True:
        raise ValueError('Production snapshot restore has not succeeded')
    matched = {x['source_sha256']: x for x in impact['matches']}
    conflicts = {x['source_sha256']: x for x in impact['conflicts']}
    if len(matched) != len(impact['matches']) or len(conflicts) != len(impact['conflicts']):
        raise ValueError('Non-unique production source mappings')
    if len({x['mongo_id'] for x in matched.values()}) != len(matched):
        raise ValueError('Multiple input files map to one production document')
    rows = []
    for item in local_plan['tracks']:
        h = item['source_sha256']
        old = matched.get(h)
        if old and old['spotify_id'] != item['spotify_id_verified_by_metadata']:
            raise ValueError('Production snapshot and source plan no longer agree')
        status = ('duplicate_alternative' if item['mapping'] == 'duplicate_alternative'
                  else 'preserve_existing_id_and_statistics' if old
                  else 'production_conflict_review' if h in conflicts
                  else 'new_track' if item['spotify_id_verified_by_metadata']
                  else 'spotify_id_unresolved')
        rows.append({**item, 'local_mapping':item['mapping'], 'production_mapping':status,
                     'production_existing_track_id':old['mongo_id'] if old else None,
                     'production_document_sha256':old['old_document_sha256'] if old else None,
                     'apply_instruction':'Preview only. Recheck production ID and document hash, preserve statistics, user references and all unrelated fields; no whole-document replacement.'})
    summary = {'source_rows':len(rows), 'production_mapping':dict(collections.Counter(x['production_mapping'] for x in rows)),
               'production_snapshot_track_count':impact['mongo_collection_counts'].get('tracks'),
               'production_snapshot_sql_counts':impact['mysql_tables']}
    return {'scope':'READ ONLY proposal based on restored production backup, not live or import-ready', 'summary':summary, 'tracks':rows}


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--plan',type=Path,required=True)
    p.add_argument('--impact',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args()
    plan=build_plan(json.loads(args.plan.read_text(encoding='utf-8')),json.loads(args.impact.read_text(encoding='utf-8')))
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'ke-hoach-production.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
    columns=['index','source_title','source_artists','source_sha256','source_spotify_id','spotify_id_verified_by_metadata','proposed_internal_key',
             'metadata_binding','production_mapping','production_existing_track_id','production_document_sha256',
             'identity_assessment','fresh_preview_verified','quality_issues','blockers','unverified_spotify_suggestions']
    with (args.output/'ke-hoach-production.csv').open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=columns,extrasaction='ignore');w.writeheader()
        for x in plan['tracks']:
            w.writerow({**x,'blockers':'; '.join(x['blockers']),'unverified_spotify_suggestions':json.dumps(x['unverified_spotify_suggestions'],ensure_ascii=False)})
    print(json.dumps(plan['summary'],ensure_ascii=False,indent=2))

if __name__=='__main__':main()
