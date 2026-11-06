import pathlib
import importlib.util
import unittest

path = pathlib.Path(__file__).with_name("release_policy.py")
spec = importlib.util.spec_from_file_location("release_policy", path)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class SeqReleasePolicyTest(unittest.TestCase):
    def test_first_stable_release_from_main(self):
        result = policy.plan("stable", "", "", 80)
        self.assertEqual(result["SEQ_TAG"], "v0.3.0")
        self.assertEqual(result["SEQ_VERSION_CODE"], "100080")
    def test_stable_monotonically_increments(self):
        self.assertEqual(policy.plan("stable", "v0.3.9", "", 101)["SEQ_TAG"], "v0.3.10")
    def test_requested_stable_must_advance(self):
        with self.assertRaises(ValueError):
            policy.plan("stable", "v0.3.9", "0.3.9", 102)
    def test_experimental_does_not_advance_stable(self):
        result = policy.plan("experimental", "v0.3.1", "", 104)
        self.assertEqual(result["SEQ_TAG"], "experimental-pr-1-run-104")
        self.assertEqual(result["SEQ_VERSION"], "0.3.1")
    def test_rejects_manual_version_on_experimental(self):
        with self.assertRaises(ValueError):
            policy.plan("experimental", "", "2.0.0", 105)
    def test_rejects_invalid_version_and_negative_run(self):
        with self.assertRaises(ValueError):
            policy.plan("stable", "v0.3.0", "3.0;rm -rf", 106)
        with self.assertRaises(ValueError):
            policy.plan("stable", "", "", -1)


if __name__ == "__main__":
    unittest.main()
