#!/usr/bin/env python3
"""Restore public signed objects into a local staging tree, without a signing key."""
import argparse
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'market/scripts'))
import market
parser=argparse.ArgumentParser()
parser.add_argument('backup',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--public-key',type=Path,required=True)
args=parser.parse_args()
market.verify(args.backup/'catalog.json',args.backup/'catalog.signature.json',args.public_key)
catalog=json.loads((args.backup/'catalog.json').read_text())
# This acceptance backup contains the controlled fixed release and provenance.
market.verify(args.backup/'plugin.zip',args.backup/'plugin.signature.json',args.public_key)
metadata=json.loads((args.backup/'plugin.signature.json').read_text())
record=next(r for r in catalog['releases'] if all(r[k]==metadata[k] for k in ('catalogPluginId','publisherId','releaseId','versionCode')))
manifest=market.read_release(args.backup/'plugin.zip')
assert all(manifest[k]==record[k] for k in ('catalogPluginId','publisherId','releaseId','versionCode'))
args.output.mkdir(parents=True,exist_ok=True)
for name in ('catalog.json','catalog.signature.json','plugin.zip','plugin.signature.json','review.json'):
 shutil.copy2(args.backup/name,args.output/name)
print('Verified backup restored without private signing key: '+str(args.output))
