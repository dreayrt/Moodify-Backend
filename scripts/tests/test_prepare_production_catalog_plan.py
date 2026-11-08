import importlib.util
import unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('production_plan',Path(__file__).resolve().parents[1]/'prepare_production_catalog_plan.py')
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class ProductionPlanTests(unittest.TestCase):
 def setUp(self):
  self.plan={'tracks':[{'source_sha256':'abc','spotify_id_verified_by_metadata':'sp','mapping':'new_track'}]}
  self.impact={'success':True,'matches':[{'source_sha256':'abc','spotify_id':'sp','mongo_id':'old','old_document_sha256':'digest'}],'conflicts':[],'mongo_collection_counts':{'tracks':1},'mysql_tables':{}}
 def test_preserves_id_and_stats_without_writing(self):
  row=mod.build_plan(self.plan,self.impact)['tracks'][0]
  self.assertEqual(row['production_mapping'],'preserve_existing_id_and_statistics')
  self.assertEqual(row['production_existing_track_id'],'old')
  self.assertEqual(row['production_document_sha256'],'digest')
 def test_rejects_stale_mapping(self):
  self.impact['matches'][0]['spotify_id']='other'
  with self.assertRaises(ValueError):mod.build_plan(self.plan,self.impact)
 def test_rejects_failed_restore(self):
  self.impact['success']=False
  with self.assertRaises(ValueError):mod.build_plan(self.plan,self.impact)

if __name__=='__main__':unittest.main()
