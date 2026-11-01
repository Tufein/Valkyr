import unittest

from release_policy import tag_for_index, validate


def history(count):
    return [dict(tag_name=tag_for_index(i), draft=False, prerelease=False,
                 published_at=f"2026-10-{i + 1:02}T00:00:00Z",
                 body=f'<!-- dogmatix-release: {{"versionCode":{i + 12}}} -->')
            for i in range(count)]


class ReleasePolicyTest(unittest.TestCase):
    def test_first_release(self):
        validate("v1.0.0", 12, [])

    def test_nine_rolls_over_to_next_major(self):
        validate("v2.0.0", 22, history(10))

    def test_republish_latest_is_allowed(self):
        validate("v2.1.0", 23, history(12))

    def test_suffix_minor_ten_or_skipped_version_rejected(self):
        for tag in ("v2.0.0-beta.1", "v1.10.0", "v2.2.0", "v0.0.0", "v1.1.1"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate(tag, 40, history(10))

    def test_next_major_may_skip_the_rest_of_a_major(self):
        validate("v3.0.0", 40, history(12))

    def test_history_with_a_major_jump_is_valid(self):
        releases = history(12)
        releases.append(dict(tag_name="v3.0.0", draft=False, prerelease=False, published_at="2026-11-01T00:00:00Z",
                             body='<!-- dogmatix-release: {"versionCode":40} -->'))
        validate("v3.1.0", 41, releases)
        with self.assertRaises(ValueError):
            validate("v3.2.0", 41, releases)

    def test_android_build_number_cannot_go_backwards(self):
        with self.assertRaises(ValueError):
            validate("v2.2.0", 22, history(12))

    def test_republish_cannot_replace_a_release_with_another_build(self):
        with self.assertRaises(ValueError):
            validate("v2.1.0", 34, history(12))

    def test_prerelease_or_broken_history_rejected(self):
        for field, value in (("prerelease", True), ("tag_name", "v9.0.0"), ("body", "")):
            releases = history(12)
            releases[-1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate("v2.2.0", 34, releases)


if __name__ == "__main__":
    unittest.main()
