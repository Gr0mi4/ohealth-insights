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

## Android exporter 0.6.2

The default export is a compact, gzip-compressed synchronization stream:

- The first successful sync covers all readable history.
- Later syncs use a Health Connect changes token and export only affected dates, updates, and deletion identifiers.
- The checkpoint is stored on-device only after a successful save or Drive upload, preventing gaps after a cancelled or failed transfer.
- If a record type fails part-way through its pages, the checkpoint is held at its previous position and the cursor is dropped, so the next sync re-reads the range through overlap recovery instead of stepping over the missing pages forever.
- If a changes token expires or the change cursor itself fails, the app requests a fresh cursor and performs a bounded recovery from the previous successful export. A cursor that fails is never stored again, which is what previously turned one bad token into a permanent recovery loop.
- Heart rate is retained only when associated with an exercise or sleep session.
- Steps are represented as one deduplicated Health Connect total and one OHealth total per day.
- Total calories are represented per day and per exercise session.
- Sleep-associated oxygen saturation and respiratory rate remain granular.
- A manual full raw diagnostic export remains available for discovery and completeness checks.
- The initial history floor defaults to 2025-04-01, but can be changed with the **History starts** date picker on the main screen to import older Health Connect/Zepp Life history.
- Changing the history start date explicitly clears the incremental checkpoint, so the next compact sync safely rebuilds the selected period without deleting earlier export files.
- Compact sync skips the 41-type discovery probe; probing remains available only in the full raw diagnostic export.

### Google Drive auto-upload

Drive authorization is persisted independently from the optional Google account email. This
prevents a successful Drive-only OAuth grant from appearing disconnected after the settings
screen is reopened. The launcher icon carries the installed `0.6.2` version badge.

The main screen and Drive settings show the last successfully completed Drive bundle write. The
timestamp is persisted only after every file in the bundle has received a successful Drive API
response, so a scheduled attempt, connection test, partial upload, or failed upload cannot move it.
The same record includes whether the trigger was manual or automatic, the sync mode, file count, and
app version in the copied diagnostics log.

After each compact sync, the app can upload three artifacts to a Drive folder tree it creates and owns (`drive.file` scope):

- **Archive/** — raw `.ndjson.gz` export (full data backup)
- **Reports/** — dated Markdown report and CSV metrics table for ChatGPT
- **Reports/** — rolling `ohealth-latest-report.md` and `ohealth-latest-metrics.csv` updated on every sync

**Test connection** provisions the whole folder tree and writes `ohealth-connection-test.txt` into the
root folder, so a passing test proves upload access rather than folder-creation access alone.

Drive permits several files to share a name in one folder, so every report and CSV is written by name:
a second sync on the same day replaces that day's file instead of adding a second copy next to it.
`Archive/` keeps the 60 most recent raw exports and drops older ones, since the reports are derived
from the on-device metrics rather than from the archives.

### Reported metrics

Daily steps and calories come from Health Connect aggregates and are overwritten on every sync.
Workouts and sleep are tracked per session id rather than as running totals, so re-reading a day —
after a retry, an overlap recovery, or a session that straddles two 30-day ranges — leaves the numbers
unchanged. Before 0.6.0 each replay added to them, which inflated workout counts and sleep minutes in
the report and CSV.

Short low-energy generic `Workout` sessions are retained in the raw stream but excluded from daily
workout totals. A generic session within 30 minutes of a named workout is also treated as an
auto-detected fragment. The Markdown report lists excluded sessions separately so the filtering is
visible rather than destructive.

Sleep reports distinguish actual asleep time from the full Health Connect session window. Actual
sleep is the sum of sleeping, light, deep, and REM stages; awake and out-of-bed stages are excluded.
When OHealth omits stages from Health Connect, actual sleep stays unknown and the report exposes the
session as **Time in bed** instead of presenting it as measured sleep.

Upgrading to 0.6.1 keeps historical steps and calories and resets workout and sleep figures, which
show as `—` until the next sync covers those days again. The old file stored totals without session
ids, so there is nothing to recompute them from.

### Daily automatic sync

Enable **Sync automatically once a day** in Drive upload settings to run the compact sync without
opening the app. It requires auto-upload, a connected Drive account, Health Connect background read
access, and one completed sync.

The first full history export always stays manual: it can exceed the ten minutes WorkManager allows a
background worker. Automatic runs only ever take the incremental path.

Failures retry with exponential backoff and never advance the checkpoint, so a failed run re-exports
the same range rather than losing data. A run that uploads successfully but could not move the
checkpoint forward is treated the same way, because otherwise a daily sync could report success
indefinitely while standing still. Success is silent; a notification appears only after repeated
failures or when Drive access has to be granted again.

A background run skips itself while the app is syncing in the foreground, so the two never write the
same checkpoint, metrics file, or Drive folder at once.

Configure OAuth and folder naming in **Drive upload settings**. See [docs/GOOGLE_DRIVE_SETUP.md](docs/GOOGLE_DRIVE_SETUP.md) for Google Cloud setup, SHA-1 registration, and ChatGPT connector instructions.

When auto-upload is enabled, the sync checkpoint is saved only after a successful Drive upload. If upload fails, open Drive settings to reconnect or disable auto-upload and save the export manually.

Exports use schema version 3 and the `.ndjson.gz` format. Incremental consumers should upsert records by Health Connect record ID, replace derived daily rows by date, and apply emitted deletion identifiers.

### How a long sync stays bounded

Reading a multi-month history in one pass is what made earlier builds appear to hang, so every
sync is now bounded on four axes:

- Work is split into 30-day ranges. Each range is a visible progress step and produces its own `range_summary` record.
- Heart rate, oxygen saturation, and respiratory rate are read **inside merged workout and sleep windows only**, instead of reading a continuous series across the whole range and discarding most of it.
- Daily aggregation is chunked into 45-day requests, keeping every request well below Health Connect's 5,000-group limit.
- Record-id deduplication uses a bounded cache of 64-bit digests rather than the id strings themselves, which covers 400,000 records for roughly half the memory the strings needed. The change cursor stops after 200 pages, and each Health Connect call is capped at 60 seconds.

### Debugging an export

- The screen shows the current stage, elapsed time, and the most recent stages, sampled twice per second rather than pushed from every Health Connect call.
- **Copy diagnostics log** puts the app version, device, Health Connect availability, and the full timestamped stage history on the clipboard.
- The export file carries `range_summary`, `type_summary` (status, record count, pages read, duration), `slow_stage`, and `export_summary` records.
- `adb logcat -s OHealthExport` streams every Health Connect call and warns on any stage slower than five seconds.

## Known source limitations

- OHealth currently writes sleep sessions without sleep-stage entries to Health Connect.
- OHealth currently exposes exercise sessions without route data through Health Connect.

These fields remain part of discovery and are not assumed to be permanently unavailable through every possible OHealth integration surface.
