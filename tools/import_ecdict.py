#!/usr/bin/env python3
"""Import a pinned ECDICT CSV into the app's versioned read-only SQLite format."""

import argparse
import csv
from contextlib import closing
import hashlib
import json
import sqlite3
from pathlib import Path


SOURCE_COMMIT = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
SOURCE_SHA256 = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
SCHEMA_VERSION = "1"
FIELDS = ("word", "phonetic", "definition", "translation", "pos", "bnc", "frq")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _optional_int(value: str | None) -> tuple[int | None, bool]:
    if not value or not value.strip():
        return None, False
    try:
        return int(value), False
    except ValueError:
        return None, True


def import_csv(source: Path, output: Path, *, expected_sha256: str | None = SOURCE_SHA256) -> dict[str, int | str]:
    source_path = source.resolve()
    output_path = output.resolve()
    if source_path == output_path:
        raise ValueError("Source CSV and output database must be different files")
    repository_root = Path(__file__).resolve().parents[1]
    try:
        output_path.relative_to(repository_root)
    except ValueError:
        pass
    else:
        raise ValueError("Output database must be kept outside the Git repository")

    actual_sha256 = sha256(source)
    if expected_sha256 and actual_sha256.lower() != expected_sha256.lower():
        raise ValueError(f"Source SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.unlink(missing_ok=True)
    imported = skipped = invalid_bnc = invalid_frq = 0
    with source.open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        missing = set(FIELDS) - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"CSV is missing required fields: {sorted(missing)}")
        with closing(sqlite3.connect(output_path)) as db, db:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute("PRAGMA page_size=4096")
            db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            db.execute(
                "CREATE TABLE dictionary ("
                "word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, "
                "phonetic TEXT NOT NULL, definition TEXT NOT NULL, "
                "translation TEXT NOT NULL, pos TEXT NOT NULL, bnc INTEGER, frq INTEGER"
                ") WITHOUT ROWID"
            )
            batch = []
            for row in reader:
                word = (row.get("word") or "").strip()
                if not word:
                    skipped += 1
                    continue
                bnc, bad_bnc = _optional_int(row.get("bnc"))
                frq, bad_frq = _optional_int(row.get("frq"))
                invalid_bnc += int(bad_bnc)
                invalid_frq += int(bad_frq)
                batch.append((
                    word,
                    row.get("phonetic") or "",
                    row.get("definition") or "",
                    row.get("translation") or "",
                    row.get("pos") or "",
                    bnc,
                    frq,
                ))
                if len(batch) >= 5000:
                    db.executemany(
                        "INSERT OR REPLACE INTO dictionary VALUES (?, ?, ?, ?, ?, ?, ?)", batch
                    )
                    imported += len(batch)
                    batch.clear()
            if batch:
                db.executemany("INSERT OR REPLACE INTO dictionary VALUES (?, ?, ?, ?, ?, ?, ?)", batch)
                imported += len(batch)
            unique_count = db.execute("SELECT COUNT(*) FROM dictionary").fetchone()[0]
            db.execute("CREATE TABLE dictionary_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.executemany(
                "INSERT INTO dictionary_metadata(key, value) VALUES (?, ?)",
                (("schema_version", SCHEMA_VERSION),
                 ("dataset_version", SOURCE_COMMIT),
                 ("source_sha256", actual_sha256),
                 ("license", "MIT; see ECDICT-NOTICE.txt")),
            )
            db.commit()

    return {
        "dataset_version": SOURCE_COMMIT,
        "schema_version": SCHEMA_VERSION,
        "source_sha256": actual_sha256,
        "source_rows": imported + skipped,
        "unique_words": unique_count,
        "skipped_blank_words": skipped,
        "duplicate_rows_replaced": imported - unique_count if imported >= unique_count else 0,
        "invalid_bnc_values": invalid_bnc,
        "invalid_frq_values": invalid_frq,
        "source_bytes": source.stat().st_size,
        "database_bytes": output_path.stat().st_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path, help="ECDICT CSV at the pinned source revision")
    parser.add_argument("--database", required=True, type=Path, help="Output file outside the Git repository")
    parser.add_argument("--allow-unpinned-hash", action="store_true", help="Skip the pinned-source checksum (for local fixtures only)")
    args = parser.parse_args()
    try:
        result = import_csv(args.csv, args.database, expected_sha256=None if args.allow_unpinned_hash else SOURCE_SHA256)
    except (OSError, sqlite3.Error, ValueError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
