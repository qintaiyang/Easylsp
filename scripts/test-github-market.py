#!/usr/bin/env python3
"""Protected publisher must bind the retained artifact to an authorized exact PR."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'market/scripts'))
import github_market as pipeline

class ReviewBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root/'publishers').mkdir()
        (self.root/'publishers/author.json').write_text(json.dumps(dict(githubAccountId=5, repositoryIds=[7])))
        self.submission = dict(catalogPluginId='demo', publisherId='author', releaseId='one', versionCode=1, repository='owner/plugins', sourceCommit='a'*40, assetId=9, githubReleaseId=11)
        self.review = dict(pr=1, prHead='b'*40, submissionPath='submissions/one.json', accountId=5, repositoryId=7, sourceCommit='a'*40, assetId=9, githubReleaseId=11, manifest={**{k:self.submission[k] for k in ('catalogPluginId','publisherId','releaseId','versionCode')}, 'sourceUrl':'https://github.com/owner/plugins/tree/'+'a'*40})
        self.pr = dict(merged=True,head=dict(sha='b'*40,repo=dict(full_name='owner/market')),user=dict(id=5))
        self.run = dict(head_sha='b'*40)
        self.files = [dict(filename='submissions/one.json')]
        self.author = dict(private=False,id=7,owner=dict(id=5))
    def tearDown(self): self.directory.cleanup()
    def api(self,path):
        if '/files?' in path: return self.files
        if '/contents/' in path: return dict(content=base64.b64encode(json.dumps(self.submission).encode()).decode())
        if path == '/repos/owner/plugins': return self.author
        raise AssertionError('unexpected API call '+path)
    def verify(self):
        with patch.object(pipeline,'ROOT',self.root),patch.object(pipeline,'api',self.api):
            pipeline.verify_review(self.pr,self.run,self.review,self.submission)
    def test_exact_checked_authorized_candidate(self): self.verify()
    def test_pr_cannot_change_publishing_code(self):
        self.files.append(dict(filename='.github/workflows/candidate.yml'))
        with self.assertRaisesRegex(ValueError,'only its fixed submission'): self.verify()
    def test_stale_run_head_is_rejected(self):
        self.run['head_sha']='c'*40
        with self.assertRaisesRegex(ValueError,'exact checked PR'): self.verify()
    def test_transferred_repository_cannot_keep_authority(self):
        self.author['owner']['id']=88
        with self.assertRaisesRegex(ValueError,'ownership mismatch'): self.verify()
    def test_artifact_cannot_substitute_source_commit(self):
        self.review['sourceCommit']='c'*40
        with self.assertRaisesRegex(ValueError,'review object mismatch'): self.verify()
    def test_retained_submission_cannot_replace_pr_content(self):
        retained=copy.deepcopy(self.submission);retained['assetId']=99
        with patch.object(pipeline,'ROOT',self.root),patch.object(pipeline,'api',self.api):
            with self.assertRaisesRegex(ValueError,'differs from exact PR'): pipeline.verify_review(self.pr,self.run,self.review,retained)
if __name__ == '__main__': unittest.main()
