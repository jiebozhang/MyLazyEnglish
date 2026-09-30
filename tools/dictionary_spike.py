#!/usr/bin/env python3
"""Build a compact ECDICT SQLite sample and measure warm exact-lemma lookups."""

import argparse
import csv
import json
import math
import random
import sqlite3
import statistics
import time
from pathlib import Path


FIELDS = ("word", "phonetic", "definition", "translation", "pos", "bnc", "frq")


def build_database(csv_path: Path, database_path: Path) -> tuple[int, list[str]]:
    database_path.parent.mkdir(parents=True, exist_ok=True)
    database_path.unlink(missing_ok=True)
    words: list[str] = []
    with csv_path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source)
        missing = set(FIELDS) - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"CSV is missing required fields: {sorted(missing)}")
        with sqlite3.connect(database_path) as db:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute(
                "CREATE TABLE dictionary ("
                "word TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, "
                "phonetic TEXT NOT NULL, definition TEXT NOT NULL, "
                "translation TEXT NOT NULL, pos TEXT NOT NULL, "
                "bnc INTEGER, frq INTEGER) WITHOUT ROWID"
            )
            db.executemany(
                "INSERT OR REPLACE INTO dictionary "
                "(word, phonetic, definition, translation, pos, bnc, frq) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (
                    (
                        row["word"], row["phonetic"], row["definition"],
                        row["translation"], row["pos"],
                        _optional_int(row["bnc"]), _optional_int(row["frq"]),
                    )
                    for row in reader
                    if row["word"]
                ),
            )
            db.commit()
            words = [row[0] for row in db.execute("SELECT word FROM dictionary")]
    return len(words), words


def _optional_int(value: str | None) -> int | None:
    try:
        return int(value) if value else None
    except ValueError:
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path, help="Pinned ECDICT CSV input")
    parser.add_argument("--database", required=True, type=Path, help="Output SQLite file")
    parser.add_argument("--queries", type=int, default=10_000)
    parser.add_argument("--seed", type=int, default=20260925)
    args = parser.parse_args()

    if args.queries < 1:
        parser.error("--queries must be positive")

    row_count, words = build_database(args.csv, args.database)
    rng = random.Random(args.seed)
    queries = [rng.choice(words) for _ in range(args.queries)]
    samples_ns: list[int] = []
    with sqlite3.connect(f"file:{args.database.resolve().as_posix()}?mode=ro", uri=True) as db:
        statement = "SELECT phonetic, pos, translation FROM dictionary WHERE word = ?"
        for word in queries:
            started = time.perf_counter_ns()
            result = db.execute(statement, (word,)).fetchone()
            samples_ns.append(time.perf_counter_ns() - started)
            if result is None:
                raise RuntimeError(f"Expected sample lookup to hit: {word!r}")

    ordered_ms = sorted(value / 1_000_000 for value in samples_ns)
    p50 = statistics.median(ordered_ms)
    p95 = ordered_ms[math.ceil(len(ordered_ms) * 0.95) - 1]
    raw_size = args.csv.stat().st_size
    sqlite_size = args.database.stat().st_size
    print(json.dumps({
        "csv_path": str(args.csv),
        "sqlite_path": str(args.database),
        "rows": row_count,
        "query_count": args.queries,
        "seed": args.seed,
        "raw_bytes": raw_size,
        "sqlite_bytes": sqlite_size,
        "p50_ms": round(p50, 6),
        "p95_ms": round(p95, 6),
        "lookup_mode": "warm, in-process SQLite exact lemma hit; one connection",
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
