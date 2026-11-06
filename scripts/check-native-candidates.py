#!/usr/bin/env python3
"""Validate the fixed offline candidate archives once at their acceptance boundary."""
import importlib.util
from pathlib import Path

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('market', repo / 'market/scripts/market.py')
market = importlib.util.module_from_spec(spec)
spec.loader.exec_module(market)
archives = sorted((repo / 'build/native-candidates').glob('*/plugin-v3.zip'))
if not archives:
    raise SystemExit('Build the Demo and run prepare-native-candidates.py first')
for archive in archives:
    release = market.read_release(archive)
    print(f"accepted {release['releaseId']}: {archive}")
