import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from tools.import_ecdict import import_csv


class ImportEcdictTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "fixture.csv"
        self.database = self.root / "dictionary.sqlite"

    def tearDown(self):
        self.temp.cleanup()

    def test_imports_case_insensitive_index_and_metadata(self):
        self.source.write_text(
            "word,phonetic,definition,translation,pos,bnc,frq\n"
            'hello,həˈləʊ,"n. greeting","你好\n您好",int.,1,2\n'
            'HELLO,,duplicate,覆盖,n.,,3\n'
            ',,,,,,\n',
            encoding="utf-8",
        )
        result = import_csv(self.source, self.database, expected_sha256=None)
        self.assertEqual(3, result["source_rows"])
        self.assertEqual(1, result["unique_words"])
        self.assertEqual(1, result["skipped_blank_words"])
        self.assertEqual(0, result["invalid_bnc_values"])
        self.assertEqual(0, result["invalid_frq_values"])
        with closing(sqlite3.connect(f"file:{self.database.as_posix()}?mode=ro", uri=True)) as db, db:
            row = db.execute("SELECT translation FROM dictionary WHERE word = ?", ("hello",)).fetchone()
            metadata = dict(db.execute("SELECT key, value FROM dictionary_metadata"))
        self.assertEqual("覆盖", row[0])
        self.assertEqual("1", metadata["schema_version"])
        self.assertEqual("bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b", metadata["dataset_version"])

    def test_rejects_missing_required_columns(self):
        self.source.write_text("word,translation\nhello,你好\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing required fields"):
            import_csv(self.source, self.database, expected_sha256=None)

    def test_refuses_database_output_inside_repository(self):
        repository_output = Path(__file__).resolve().parents[1] / "forbidden-dictionary.sqlite"
        with self.assertRaisesRegex(ValueError, "outside the Git repository"):
            import_csv(self.source, repository_output, expected_sha256=None)


if __name__ == "__main__":
    unittest.main()
