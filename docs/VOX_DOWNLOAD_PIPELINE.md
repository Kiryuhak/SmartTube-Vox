# VOX Download Pipeline 3.0: Performance Telemetry & Persistence

## 1. Overview
VOX Downloads 3.0 provides end-to-end performance timing persistence, multi-stream throughput tracking, and de-duplicated health reporting.

## 2. Granular Stage Timings & Metrics Persistence
All jobs persist structured performance metrics into `job.json`:
- `downloadElapsedMs`: Total wall-clock time spent downloading chunks.
- `videoElapsedMs`: Video track network retrieval time.
- `audioElapsedMs`: Original audio track retrieval time.
- `translationElapsedMs`: Translated voice track retrieval time.
- `packagingElapsedMs`: MKV muxing and EBML packaging duration.
- `finalizeElapsedMs`: Output verification, atomic rename, and MediaStore publishing duration.
- `averageSpeedMbps`: Overall throughput in Mbps.
- `peakSpeedMbps`: Peak instantaneous chunk throughput.
- `stallEventsCount`: Network throughput drops (<100 KB/s).
- `rangeResumptionsCount`: HTTP Range header resumes on interrupted downloads.
- `networkReconnectCount`: Socket reconnect occurrences.
- `retryCount`: Chunk-level retry attempts.

## 3. Storage Invariants & Spam Elimination
- **Root Cause Fixed**: Previously, on app launch, `restoreJobsFromStorage()` scanned on-disk jobs and logged `DOWNLOAD_STATE_INCONSISTENT` every time for old unfinished jobs, spamming diagnostic logs.
- **Deduplication Set**: `reportedInconsistentJobs` ensures any inconsistent job is reported at most once per process lifetime.
- **Ring Buffer Deduplication**: Consecutive identical `IMAGE_REQUEST_FAILED` and `DOWNLOAD_STATE_INCONSISTENT` errors coalesce with `repeatCount`, `firstSeen`, and `lastSeen`.
