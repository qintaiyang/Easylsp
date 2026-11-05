#!/usr/bin/env python3
"""The on-device constructor result, not compilation or a task handle, is the oracle."""
import json
import sys
from pathlib import Path
publication = json.loads(Path(sys.argv[1]).read_text())
lines = Path(sys.argv[2]).read_text().splitlines()
events = [(i, json.loads(line)) for i, line in enumerate(lines) if line.startswith('{"schema":1,')]
plugin = publication['plugins'][0]
matching = [(i, event) for i, event in events if event['buildId'] == plugin['buildId'] and event['generation'] == publication['generation']]
assert matching, 'No runtime evidence for the published build/generation'
assert len({event['instanceId'] for _, event in matching}) == 1
assert all(event['uid'] == publication['uid'] and event['startTicks'] > 0 for _, event in matching)
assert all(event['sourceRevision'] == plugin['sourceRevision'] for _, event in matching)
phases = {event['phase']: (i, event) for i, event in matching}
for phase in ['plugin_loading', 'plugin_initialized', 'hook_installed', 'hook_hit']:
    assert phases[phase][1]['code'] == 0, phases[phase]
constructor = lines.index('constructor result=1010 errno=33')
jni = lines.index('JNI_OnLoad constructor_result=1010')
assert phases['plugin_loading'][0] < phases['plugin_initialized'][0] < phases['hook_installed'][0] < phases['hook_hit'][0] < constructor < jni
assert phases['hook_installed'][1]['hookId'] == phases['hook_hit'][1]['hookId']
assert not any(event['phase'].endswith('failed') for _, event in matching)
print(json.dumps(dict(result='PASS', buildId=plugin['buildId'], generation=publication['generation'], constructorResult=1010, errno=33)))
