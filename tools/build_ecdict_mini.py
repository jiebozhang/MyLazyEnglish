#!/usr/bin/env python3
"""Build a deterministic, compressed ECDICT subset from the pinned SQLite import."""

import argparse
import gzip
import hashlib
import json
import sqlite3
from pathlib import Path

SOURCE_COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
SOURCE_SHA256 = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
SCHEMA_VERSION = 1
REQUIRED_WORDS = (
    "run", "jump", "give up", "look after", "look for", "look at", "look into",
    "turn on", "turn off", "take off", "get up", "find out", "pick up", "put on",
    "come back", "go on",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build(source: Path, output_gzip: Path, limit: int) -> dict[str, object]:
    if limit < len(REQUIRED_WORDS):
        raise ValueError(f"--limit must be at least {len(REQUIRED_WORDS)}")
    output_gzip.parent.mkdir(parents=True, exist_ok=True)
    output_db = output_gzip.with_suffix("")
    if output_db.suffix != ".sqlite":
        raise ValueError("Output must be named ecdictionary.sqlite.gzip (or .gz outside Android assets)")
    source_db = sqlite3.connect(f"file:{source.resolve().as_posix()}?mode=ro", uri=True)
    output_db.unlink(missing_ok=True)
    target_db = sqlite3.connect(output_db)
    try:
        source_hash = source_db.execute(
            "SELECT value FROM dictionary_metadata WHERE key='source_sha256'"
        ).fetchone()
        source_version = source_db.execute(
            "SELECT value FROM dictionary_metadata WHERE key='dataset_version'"
        ).fetchone()
        if source_hash != (SOURCE_SHA256,) or source_version != (SOURCE_COMMIT,):
            raise ValueError("Input SQLite is not the pinned ECDICT source package")
        target_db.execute("PRAGMA page_size=4096")
        target_db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        target_db.execute(
            "CREATE TABLE dictionary ("
            "word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, phonetic TEXT NOT NULL, "
            "definition TEXT NOT NULL, translation TEXT NOT NULL, pos TEXT NOT NULL, "
            "bnc INTEGER, frq INTEGER) WITHOUT ROWID"
        )
        target_db.execute("CREATE TABLE dictionary_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        frequent = source_db.execute(
            "SELECT word, phonetic, definition, translation, pos, bnc, frq FROM dictionary "
            "WHERE COALESCE(bnc, 0) > 0 OR COALESCE(frq, 0) > 0 "
            "ORDER BY min(CASE WHEN bnc > 0 THEN bnc ELSE 2147483647 END, "
            "CASE WHEN frq > 0 THEN frq ELSE 2147483647 END), word COLLATE NOCASE LIMIT ?",
            (limit,),
        ).fetchall()
        selected = {row[0].casefold(): row for row in frequent}
        for word in REQUIRED_WORDS:
            row = source_db.execute(
                "SELECT word, phonetic, definition, translation, pos, bnc, frq "
                "FROM dictionary WHERE word=? COLLATE NOCASE", (word,),
            ).fetchone()
            if row is None:
                raise ValueError(f"Required ECDICT entry is missing: {word}")
            selected[row[0].casefold()] = row
        target_db.executemany("INSERT INTO dictionary VALUES (?, ?, ?, ?, ?, ?, ?)", selected.values())
        target_db.executemany(
            "INSERT INTO dictionary_metadata(key, value) VALUES (?, ?)",
            (("schema_version", str(SCHEMA_VERSION)), ("dataset_version", SOURCE_COMMIT),
             ("source_sha256", SOURCE_SHA256), ("license", "MIT; see ECDICT-NOTICE.txt")),
        )
        target_db.commit()
        integrity = target_db.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise ValueError(f"Generated SQLite integrity check failed: {integrity}")
    finally:
        source_db.close()
        target_db.close()

    with output_db.open("rb") as raw, output_gzip.open("wb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", mtime=0, compresslevel=9) as stream:
            for chunk in iter(lambda: raw.read(1024 * 1024), b""):
                stream.write(chunk)
    database_hash = sha256(output_db)
    package_hash = sha256(output_gzip)
    output_gzip.with_name("ecdictionary.sqlite.sha256").write_text(database_hash + "\n", encoding="ascii")
    output_gzip.with_name("dictionary-package.json").write_text(json.dumps({
        "dataset_version": SOURCE_COMMIT, "source_sha256": SOURCE_SHA256,
        "database_sha256": database_hash, "package_sha256": package_hash,
        "entries": len(selected), "database_bytes": output_db.stat().st_size,
        "compressed_bytes": output_gzip.stat().st_size, "license_notice": "ECDICT-NOTICE.txt",
    }, indent=2) + "\n", encoding="utf-8")
    output_db.unlink()
    return json.loads(output_gzip.with_name("dictionary-package.json").read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path, help="Full SQLite generated by tools/import_ecdict.py")
    parser.add_argument("--output", required=True, type=Path, help=".../ecdictionary.sqlite.gzip")
    parser.add_argument("--limit", type=int, default=20_000)
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.database, args.output, args.limit), indent=2))
    except (OSError, sqlite3.Error, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
