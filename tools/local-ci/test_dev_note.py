"""A dev build is dispatched only with its own What's New note."""
import importlib.util
from pathlib import Path
import sys
import unittest
sys.dont_write_bytecode = True
SOURCE = Path(__file__).resolve().parent / 'run-release.py'
spec = importlib.util.spec_from_file_location('review_release_dev_note', SOURCE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class DevNote(unittest.TestCase):
    def test_the_next_run_numbers_the_build_that_needs_a_note(self):
        self.assertEqual(release.missing_dev_note([], '0.19.0', [{'run_number': 92}]), '0.19.0-dev.93')

    def test_a_note_for_that_build_lets_it_through(self):
        notes = [{'version': '0.19.0-dev.93'}, {'version': '0.19.0'}]
        self.assertIsNone(release.missing_dev_note(notes, '0.19.0', [{'run_number': 92}]))

    def test_the_last_build_s_note_is_not_enough(self):
        notes = [{'version': '0.19.0-dev.92'}, {'version': '0.19.0'}]
        self.assertEqual(release.missing_dev_note(notes, '0.19.0', [{'run_number': 92}]), '0.19.0-dev.93')

    def test_the_first_run_is_number_one(self):
        self.assertEqual(release.missing_dev_note([], '0.19.0', []), '0.19.0-dev.1')


if __name__ == '__main__':
    unittest.main()
