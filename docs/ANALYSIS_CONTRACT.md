# ChatGPT Analysis Contract

This document defines how generated OHealth Insights reports should be interpreted for personal analysis. It is intentionally free of personal health values, schedules, or other private data so it can live in the public repository.

## Daily steps

1. `stepsOHealth` is the canonical user-facing daily step count when it is available.
2. `stepsTotal` is a cross-source/deduplicated diagnostic value. It must not silently replace `stepsOHealth` in normal summaries.
3. If `stepsOHealth` is missing, `stepsTotal` may be used as an explicit fallback and should be labelled as such.
4. A current-day row is partial when the report was exported before that local day ended. Do not compare a partial day directly with completed days without saying so.
5. When a newly reported value disagrees with an older report for the same date, prefer the newer completed sync and treat the earlier value as a synchronization snapshot rather than a biological/activity change.

## Calories

1. `caloriesOHealthKcal` is the only canonical calorie value in generated reports.
2. It is the sum of calorie records written by `com.heytap.health.international`, read directly.
   Health Connect aggregation is deliberately not used: it fills every minute no record covers with
   energy derived from basal metabolic rate, and the data origin filter does not remove that filler
   because derived energy belongs to no origin. On a typical day the filler exceeded the measured
   value, inflating roughly 900 kcal to roughly 2100.
3. Records are bucketed by the local calendar day of their own `startZoneOffset`, not by UTC.
4. OHealth writes each workout twice, as per-minute records and as one summary record spanning the
   session. Any record whose span strictly contains another record's span is dropped, so a session
   is counted once. 188 of 524 days were affected before this rule existed.
5. The resulting value matches the active-calorie figure shown in the OHealth app, verified to the
   kilocalorie on several days.
6. OHealth publishes no `ActiveCaloriesBurnedRecord` at all; its `TotalCaloriesBurnedRecord` data is
   what the app presents as active calories. `caloriesOHealthRecordType` names the type actually
   used, and active records are still preferred should they ever appear.
7. Data written by Google Fit or other apps is never included.
8. `caloriesOHealthCoveredMinutes` is how many minutes of the day carry a record. It measures wrist
   time, not activity: a day with 300 covered minutes and one with 900 are not comparable, and a low
   figure must never be read as a sedentary day.
9. A current-day calorie value may be partial when synchronization runs before the local day ends.

## Exercise sessions versus training sessions

Health Connect/OHealth exercise records are activity records, not automatically distinct training sessions.

1. Do not report the raw exercise-record count as the number of workouts without semantic classification.
2. Named activities such as outdoor running, cycling, tennis, hiking, etc. are strong activity-type signals, but overlapping/adjacent generic records can still be duplicates or fragments.
3. Generic `Workout` records may represent walks, warm-ups, cool-downs, adjacent fragments, or other low-intensity movement. They must not automatically count as training.
4. `Freestyle workout` is ambiguous. It must not automatically be labelled strength training or gym work.
5. Adjacent or overlapping generic records around a named session should be treated as candidate fragments/duplicates rather than additional workouts.

## Semantic activity classes

For user-facing analysis, separate at least these concepts:

- `training`: deliberate sport/strength/cardio session with meaningful training load.
- `light_activity`: deliberate movement with some physiological load but not comparable to a normal training session.
- `walk_everyday`: walking or routine movement that contributes to activity volume but not workout count.
- `unknown`: insufficient evidence to classify confidently.

A session may count toward total physical activity while not counting toward the training-session count.

## Classification evidence

When the source label is ambiguous, classification should combine available evidence instead of relying on one field:

- explicit exercise type/title;
- duration;
- exercise calories and calories per minute;
- heart-rate average, peaks, zones, and temporal pattern when available;
- steps/step rate during the session when available;
- distance and route/movement signals when available;
- overlap or adjacency with other exercise records;
- repeated temporal/context patterns;
- explicit user-provided ground truth.

User-confirmed ground truth overrides heuristic classification and should be used to improve future interpretation.

## Confidence and wording

1. Do not present an ambiguous classification as certain.
2. Prefer wording such as `likely walk`, `likely strength`, `light mixed activity`, or `unknown` when evidence is incomplete.
3. Keep raw source labels available for debugging even when a semantic class is assigned.
4. Distinguish data facts from interpretation in summaries.

## Report behavior

Generated Markdown/CSV reports should expose a canonical step count based on `stepsOHealth ?: stepsTotal`, while retaining both raw step fields for diagnostics. They should expose only OHealth-origin calories summed from records, must not include cross-source calorie aggregates, and must carry the covered-minutes figure alongside every calorie value.

Until a full semantic classifier is implemented, reports should include this contract (or a concise embedded version) so downstream ChatGPT analysis does not treat raw exercise records as literal workout counts.

## Future classifier target

The intended pipeline is:

`raw exercise records -> overlap/dedup filtering -> semantic activity classification -> load classification -> daily/weekly summary`

The classifier should eventually emit, at minimum, semantic class, confidence, source label, duration, calories, and the evidence used for classification.
