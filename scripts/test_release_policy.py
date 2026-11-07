import importlib.util
import pathlib
import unittest

src = pathlib.Path(__file__).with_name("release_policy.py")
spec = importlib.util.spec_from_file_location("release_policy", src)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class SeqReleasePolicyTest(unittest.TestCase):
    def test_first_stable_from_migration_baseline(self):
        r = policy.plan("stable", "", "", 80)
        self.assertEqual(r["SEQ_TAG"], "v0.4")
        self.assertEqual(r["SEQ_VERSION_CODE"], "100080")

    def test_stable_increment_like_mradio(self):
        self.assertEqual(policy.plan("stable", "v4.9", "", 102)["SEQ_TAG"], "v5.0")

    def test_version_override_must_advance(self):
        with self.assertRaises(ValueError):
            policy.plan("stable", "v4.9", "4.9", 103)
        with self.assertRaises(ValueError):
            policy.plan("stable", "v4.9", "3.0", 103)

    def test_experimental_from_last_stable(self):
        p = policy.plan("experimental", "v5.0", "", 104, 1, "abcdef012345")
        self.assertEqual(p["SEQ_TAG"], "experimental-pr-1-run-104")
        self.assertEqual(p["SEQ_VERSION"], "5.0")
        self.assertEqual(p["SEQ_APK_NAME"], "SEQ-PR-1-abcdef0.apk")
        self.assertEqual(p["SEQ_BUILD_VARIANT"], "Debug")

    def test_refuses_explicit_experimental_version(self):
        with self.assertRaises(ValueError):
            policy.plan("experimental", "v0.3", "1.0", 108)

    def test_refuses_invalid_input(self):
        for bad in ("0.3.0", "4.12", "3;rm", ""):
            with self.assertRaises(ValueError):
                policy.normalize(bad)
        with self.assertRaises(ValueError):
            policy.plan("stable", "", "", -1)


if __name__ == "__main__":
    unittest.main()
