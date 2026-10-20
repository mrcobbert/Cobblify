import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('preview_source', Path(__file__).with_name('preview-source.py'))
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)
SHA = 'a' * 40


class PreviewSourceTests(unittest.TestCase):
    def setUp(self):
        self.record = {'repository': {'full_name': source.REPO}, 'head_repository': {'full_name': source.REPO},
            'path': source.WORKFLOW, 'event': 'workflow_dispatch', 'actor': {'login': 'mrcobbert'},
            'triggering_actor': {'login': 'mrcobbert'}, 'status': 'completed', 'conclusion': 'success',
            'head_sha': SHA, 'run_number': 54}
        self.jobs = [{'name': name, 'id': i, 'run_attempt': 1, 'conclusion': 'success', 'head_sha': SHA}
                     for i, name in enumerate(('macos', 'windows'))]

    def test_completed_platforms_survive_ready_only_rerun(self):
        jobs = self.jobs + [{'name': 'ready', 'id': 3, 'run_attempt': 2, 'conclusion': 'skipped'}]
        source.validate_source(self.record, jobs, SHA)

    def test_wrong_source_identity_is_rejected(self):
        mutations = [('head_sha', 'b' * 40), ('event', 'pull_request'), ('path', 'other.yml'),
                     ('conclusion', 'failure'), ('status', 'in_progress'), ('run_number', 0)]
        for key, value in mutations:
            with self.subTest(key=key):
                record = copy.deepcopy(self.record); record[key] = value
                with self.assertRaises(ValueError): source.validate_source(record, self.jobs, SHA)
        for key in ('actor', 'triggering_actor', 'repository', 'head_repository'):
            with self.subTest(key=key):
                record = copy.deepcopy(self.record)
                record[key] = {'login': 'someone-else'} if 'actor' in key else {'full_name': 'other/repo'}
                with self.assertRaises(ValueError): source.validate_source(record, self.jobs, SHA)

    def test_missing_or_later_failed_platform_cannot_publish(self):
        with self.assertRaises(ValueError): source.validate_source(self.record, self.jobs[:1], SHA)
        jobs = self.jobs + [{'name': 'windows', 'id': 3, 'run_attempt': 2, 'conclusion': 'failure', 'head_sha': SHA}]
        with self.assertRaises(ValueError): source.validate_source(self.record, jobs, SHA)
        jobs = copy.deepcopy(self.jobs); jobs[0]['head_sha'] = 'b' * 40
        with self.assertRaises(ValueError): source.validate_source(self.record, jobs, SHA)


if __name__ == '__main__':
    unittest.main()
