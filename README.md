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

## Current status

Project bootstrap. The next milestone is source discovery: obtain a representative OHealth export or backup, inspect its structure, and create the first data inventory before choosing the extractor implementation.
