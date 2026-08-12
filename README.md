# OHealth Insights

OHealth Insights is a personal health-data pipeline for extracting, preserving, and analyzing data produced by OHealth and connected OnePlus devices.

## Primary goal

Extract **everything OHealth can make available** — all data types, records, fields, metadata, and relationships exposed through any practical and authorized export or integration mechanism.

The scope is discovery-driven, not limited to a predefined list such as sleep, workouts, heart rate, calories, activity, load, or recovery. Those are examples only. If OHealth exposes additional data, the project should detect, document, preserve, and make it usable.

## Product goals

- Discover every data source and export surface available from OHealth.
- Preserve the original raw data without silently dropping unknown fields.
- Build a complete, versioned catalog of discovered record types and fields.
- Normalize the data into stable, documented schemas suitable for longitudinal analysis.
- Support repeatable and incremental synchronization.
- Validate completeness, timestamps, units, duplicates, gaps, and source provenance.
- Store the resulting private datasets in user-controlled storage.
- Make the data accessible to ChatGPT for personal analysis, summaries, correlations, and trend detection.
- Keep the pipeline extensible when OHealth or the wearable introduces new data types.

## Data principle

> Collect broadly, transform conservatively, and never discard an unknown field by default.

Raw source data should remain immutable. Normalized datasets are derived views and must retain a link to their source records.

## Privacy and safety

- This public repository contains application code and documentation only.
- Personal health exports, normalized datasets, credentials, tokens, and device identifiers must never be committed.
- Secrets and private data must be stored outside GitHub in explicitly configured, user-controlled locations.
- The project provides personal analytics, not medical diagnosis.

## Intended pipeline

1. **Discover** OHealth's available exports, backups, local data, and authorized integration surfaces.
2. **Extract** complete raw records and metadata.
3. **Catalog** every discovered entity, field, unit, timestamp, and relationship.
4. **Normalize** data into versioned, analysis-friendly datasets.
5. **Sync** raw and normalized data to private user-controlled storage.
6. **Analyze** history, trends, workload, recovery, sleep, activity, and any other available signals.

## Android exporter 0.3.3

The default export is a compact, gzip-compressed synchronization stream:

- The first successful sync covers all readable history.
- Later syncs use a Health Connect changes token and export only affected dates, updates, and deletion identifiers.
- The checkpoint is stored on-device only after the user successfully saves the file, preventing gaps after a cancelled or failed save.
- If a changes token expires, the app performs a bounded recovery from the previous successful export instead of silently skipping data.
- Heart rate is retained only when associated with an exercise or sleep session.
- Dense workout heart-rate series are preferred; lower-frequency point records are fallback data when a dense series is unavailable.
- Steps are represented as one deduplicated Health Connect total and one OHealth total per day.
- Total calories are represented per day and per exercise session.
- Sleep-associated oxygen saturation and respiratory rate remain granular.
- A manual full raw diagnostic export remains available for discovery and completeness checks.
- Full-history daily aggregation is split into bounded requests to stay below Health Connect's 5,000-group limit.
- The initial history floor is 2025-04-01, matching the known beginning of this OHealth dataset and avoiding empty queries back to 1970.
- Compact sync skips the 41-type discovery probe; probing remains available only in the full raw diagnostic export.
- Every Health Connect phase reports its current type/page and elapsed time in the UI. Individual calls have bounded waits and identify the exact failed stage.
- Changes-token setup cannot block the export indefinitely. If it is unavailable, the app saves a timestamp checkpoint and uses a seven-day overlap recovery on the next sync.

Exports use schema version 3 and the `.ndjson.gz` format. Incremental consumers should upsert records by Health Connect record ID, replace derived daily rows by date, and apply emitted deletion identifiers.

## Known source limitations

- OHealth currently writes sleep sessions without sleep-stage entries to Health Connect.
- OHealth currently exposes exercise sessions without route data through Health Connect.

These fields remain part of discovery and are not assumed to be permanently unavailable through every possible OHealth integration surface.
