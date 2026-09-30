# ADR 0004: Dictionary Licensing and Sizing Spike

- Status: Accepted for the spike; dictionary source approval remains unresolved.
- Scope: T0-5 Spike 1 only; no dictionary data is included in this repository.
- Research date: 2026-09-25.

## Method

- Reviewed PRD v2.2 sections 0, 17, 19, and 22 and the T0-5 requirements in the development checklist.
- Read the ECDICT upstream README and LICENSE, the NGSL project license statements, and the Creative Commons license deeds. The upstream corpus provenance statements are recorded separately from the SPDX-style license declared by the repository.
- Benchmarked the full ECDICT CSV at pinned upstream commit `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`. The file and generated database were kept outside the repository in a temporary directory. `tools/dictionary_spike.py` imports seven app-relevant fields into a read-only-use SQLite table and executes deterministic random exact-word hits.

## License Findings

### ECDICT

The [upstream LICENSE](https://github.com/skywind3000/ECDICT/blob/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/LICENSE) declares MIT, copyright `(c) 2025 Linwei`. Its operative terms grant use, copy, modify, merge, publish, distribute, sublicense, and sale without restriction; copies or substantial portions must include the copyright and permission notice. It has no NonCommercial or ShareAlike clause. For a distribution made solely to family, those declared terms are not themselves in conflict with non-commercial side-loading. If integrated, show the ECDICT attribution and MIT notice in an in-app Licenses/About view and include the full notice with the data package.

However, the [upstream README](https://github.com/skywind3000/ECDICT/tree/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b) says the corpus began with an EDictAZ text file found online, later added a separately obtained CET/GRE vocabulary table and scraped phonetics, then incorporated the cdict 1.0-1 RPM dictionary, community contributions, BNC frequency review, and other corpus/exam labels. It does not itemize the rights or licenses for these source components or map them to fields/entries. In particular, the repository-wide MIT notice does not by itself establish that the project author could license every upstream-derived entry and annotation. The present research found no authoritative per-source provenance/permission record resolving that gap.

**Conclusion for ECDICT: cannot confirm that the bundled dataset as a whole is cleared for redistribution**, even though the repository declares an MIT license. Do not ship or commit the data unless the maintainer supplies provenance/rights confirmation or a clean-room/field-level source audit clears the included fields. This is a provenance finding, not a claim that infringement has occurred.

### NGSL

The [NGSL project home page](https://www.newgeneralservicelist.org/home) currently says all corpus-derived lists are “public domain” if properly cited, but its license section on that same page identifies *New General Service List by Browne, C., Culligan, and Phillips* as [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The project's [NGSL-S page](https://www.newgeneralservicelist.org/ngsls) also explicitly states CC BY-SA 4.0. The public-domain and CC BY-SA descriptions conflict; no clarification from the authors was found. During this review the same site also rendered unrelated casino navigation and footer/copyright text, an additional reason to verify the terms with the rights holders rather than infer intent. Treat the explicit license link as the operative published terms pending clarification, not the public-domain sentence.

CC BY-SA 4.0 allows sharing and adaptation, including commercial use, subject to attribution and ShareAlike. Attribution must give appropriate credit, link the license, indicate changes, and must not imply endorsement. Adapted material shared onward must use the same or a compatible license, and no additional legal/technical restrictions may be applied. A suitable in-app notice if later authorized is:

> New General Service List by Browne, C., Culligan, B., and Phillips. Licensed under CC BY-SA 4.0. Source: https://www.newgeneralservicelist.org/home. Adapted for LazyEng: normalized word forms and mapped frequency bands; changes made in the identified data release. License: https://creativecommons.org/licenses/by-sa/4.0/ . The authors do not endorse LazyEng.

Place this in an in-app Licenses/About view and in a bundled data `NOTICE`, with an accessible copy of the list's source/version and modifications. Keep the list as a distinct data component; do not assert that the whole Android application is ShareAlike without legal analysis. Since the same official page has conflicting public-domain language and its currently served page has inconsistent maintenance signals, seek written author clarification before packaging or transforming the list.

**Conclusion for NGSL: license terms are published, but the intended grant is not fully clear due to conflicting statements. Do not treat it as approved for a release build until clarified.**

### Household sideload decision

The PRD's non-commercial household use and private sideloading do not erase license obligations when APK/data copies are transferred between family devices. ECDICT's MIT grant would generally permit those acts if rights in the underlying material were established; NGSL CC BY-SA would allow non-commercial household sharing subject to attribution and ShareAlike, but the official site's conflicting public-domain statement still needs resolution. This ADR is an engineering due-diligence record, not legal advice. **Neither source is approved for inclusion at this time.**

## Sizing and Lookup Measurement

Source: `ecdict.csv` at commit `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`.

- Raw CSV: 65,933,428 bytes (62.88 MiB), SHA-256 `1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf`.
- Imported rows: 770,611 unique headwords.
- SQLite output: 68,517,888 bytes (65.34 MiB), one `WITHOUT ROWID` table with a case-insensitive primary-key index and fields `word`, `phonetic`, `definition`, `translation`, `pos`, `bnc`, `frq`.
- 10,000 seeded random hit lookups, one in-process connection, warm cache: P50 0.1275 ms; P95 0.1641 ms. The script checks that every requested lookup hit. P95 uses the nearest-rank percentile.
- Host: Windows, Python 3.12.0, standard-library `sqlite3`. This is not an Android device benchmark and does not include app startup, Room overhead, cold I/O, or flash storage variance. Validate the PRD's <300 ms goal on target devices in E5-T1.

Reproduce after downloading the pinned CSV from:

`https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv`

```powershell
$csv = Join-Path $env:TEMP 'ecdict.csv'
$db = Join-Path $env:TEMP 'ecdict-core.sqlite'
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv' -OutFile $csv
python tools/dictionary_spike.py --csv $csv --database $db --queries 10000 --seed 20260925
```

The script uses only Python's standard library. Do not put the CSV or generated SQLite file in Git.

## Update Strategy

- ECDICT has a numbered release history (the repository describes a 3.4-million-entry 1.0.28 release) and a separate `ECDICT-update` repository described as periodic merges, but no predictable release cadence or stable machine-readable update contract was established. Git history for `ecdict.csv` shows a Jan 2025 data-file change, not a published cadence.
- The NGSL site describes its list as open to examination and revision and presents versioned lists, but this review found no dependable release schedule or machine-readable changelog. The NGSL-S page gives 1.2 as released in 2017.
- Recommendation: do not fetch dictionary updates at runtime. If rights are cleared, pin a source version/commit and SHA-256, record license and attribution metadata, run a manual quarterly source review, and publish reviewed data as a separately versioned read-only database through the checklist's controlled import/release channel. Keep the prior package until an upgrade validates successfully.

## Alternative Source

If rights for ECDICT/NGSL remain unresolved, evaluate a curated subset extracted from [English Wiktionary](https://en.wiktionary.org/wiki/Wiktionary:Copyrights). Wiktionary's own copyright page says original entry text is dual-licensed under CC BY-SA 4.0 and GFDL; its [CC BY-SA 4.0 legal text](https://en.wiktionary.org/wiki/Wiktionary:Text_of_Creative_Commons_Attribution-ShareAlike_4.0_International_License) expressly covers extraction/reuse of substantial database contents and imposes attribution/ShareAlike obligations. This is a concrete, publicly documented alternative with a clearer stated license, but it is not a drop-in English-Chinese dictionary: Chinese translation coverage and extraction quality must be sampled, each imported component must be attributed, and the data package/adaptation must preserve the applicable share-alike terms. Obtain legal review before shipment.

## Decision

**Needs an alternative / rights clarification before E5-T1 may ship dictionary data.** Do not bundle ECDICT or NGSL yet. ECDICT's declared MIT terms are permissive but its upstream source provenance is not cleared; NGSL has an explicit CC BY-SA 4.0 grant and practical attribution path, but its own site also calls the lists public domain. Use Wiktionary as the first replacement candidate for a small coverage/quality and license-compliance proof, or obtain written permission/provenance clarification from the ECDICT and NGSL rights holders. No dictionary data is committed by this spike.
