from contextlib import closing
import sqlite3
import tempfile
import unittest
from pathlib import Path

from build_ecdict_mini import REQUIRED_WORDS, SOURCE_COMMIT, SOURCE_SHA256, build, sha256


class MiniDictionaryBuilderTest(unittest.TestCase):
    def test_rebuild_is_deterministic_and_keeps_required_words(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "full.sqlite"
            with closing(sqlite3.connect(source)) as database, database:
                database.execute("CREATE TABLE dictionary (word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, phonetic TEXT NOT NULL, definition TEXT NOT NULL, translation TEXT NOT NULL, pos TEXT NOT NULL, bnc INTEGER, frq INTEGER) WITHOUT ROWID")
                database.executemany("INSERT INTO dictionary VALUES (?, ?, '', ?, 'n.', ?, ?)",
                    [(word, "/test/", "测试释义", index + 1, index + 1) for index, word in enumerate(REQUIRED_WORDS)])
                database.execute("CREATE TABLE dictionary_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                database.executemany("INSERT INTO dictionary_metadata VALUES (?, ?)",
                    (("source_sha256", SOURCE_SHA256), ("dataset_version", SOURCE_COMMIT)))
            output = root / "ecdictionary.sqlite.gz"

            first = build(source, output, len(REQUIRED_WORDS))
            first_package_hash = sha256(output)
            second = build(source, output, len(REQUIRED_WORDS))

            self.assertEqual(first, second)
            self.assertEqual(first_package_hash, sha256(output))
            self.assertEqual(len(REQUIRED_WORDS), second["entries"])
            self.assertEqual(SOURCE_COMMIT, second["dataset_version"])

    def test_rejects_database_from_an_unpinned_revision(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "wrong.sqlite"
            with closing(sqlite3.connect(source)) as database, database:
                database.execute("CREATE TABLE dictionary_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                database.executemany("INSERT INTO dictionary_metadata VALUES (?, ?)",
                    (("source_sha256", "0" * 64), ("dataset_version", SOURCE_COMMIT)))
            with self.assertRaisesRegex(ValueError, "pinned ECDICT"):
                build(source, root / "ecdictionary.sqlite.gz", 20)


if __name__ == "__main__":
    unittest.main()
