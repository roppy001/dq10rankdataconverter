# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

**Build (fat JAR):**
```
gradlew.bat build
```

**Run (CLI mode):**
```
java -cp build\libs\rankconverter-all.jar roppy.dq10.rankanalytics.converter.RankConverterMain <race-key> <round>
```

Race key and round can also be provided via env vars `RANK_RACEKEY` and `RANK_ROUND`.

**Required environment variables at runtime:**
- `FC2_FTP_PASSWORD` — FTP password for uploading output (username is hardcoded as `yumedqx`)
- AWS credentials resolved via the default AWS SDK credential chain (for S3 access)

**No tests exist** in this project.

## Architecture

This is a Dragon Quest X (DQ10) ranking data pipeline. It downloads raw ranking snapshots from S3, identifies individual players across time-series snapshots by name matching, serializes the result to JSON, compresses it with GZIP, and uploads it to an FC2 FTP server.

### Data flow

```
S3 (bucket: roppyracedata) → S3Downloader (parse TSV)
  → NameIdentifier (match players across snapshots)
  → Jackson JSON serialization → GZIP
  → FTP upload to yumedqx.web.fc2.com
```

### Entry point

`RankConverterMain` implements both AWS Lambda `RequestHandler<S3Event, Object>` and a CLI `main()`. It reads `RaceConfig` to determine which subraces and snapshot limits to use, then orchestrates the full pipeline.

### Key classes

| Class | Role |
|---|---|
| `RaceConfig` (enum) | Per-race configuration: subrace count, snapshot length limit, `NameIdentifierConfig` per subrace |
| `NameIdentifierConfig` (enum) | Tuning knobs for player tracking: `pointDiffUpperLimit`, `pointDiffLowerLimit`, `lowChangeFrequency`, `reverseOrder` |
| `S3Downloader` (singleton) | Downloads and parses TSV files from S3 into `Race`/`Subrace`/`RankSnapshot`/`RankItem` DTOs |
| `NameIdentifier` (singleton) | Multi-pass algorithm that assigns stable IDs to players across snapshots |

### NameIdentifier algorithm (the core logic)

This is the most complex part of the codebase. For each new snapshot, players are matched to known IDs using ordered fallbacks:

1. Exact name + same point (used when `lowChangeFrequency = true`, e.g. Casino Raid, Fishing)
2. Same point, with anonymous player (`（ないしょ）`) handling
3. Same name with point change within `[pointDiffLowerLimit, pointDiffUpperLimit]`
4. Anonymous-to-named transition detection (player revealed their name)
5. New player → assign new ID

`DisplayName` records the canonical name for each player ID, including an `anonymous` flag for players who always appear as `（ないしょ）`.

### DTOs (`dto/` package)

```
Race
└── List<Subrace>
    ├── List<RankSnapshot>   ← time series, trimmed to RaceConfig.snapshotLength
    │   └── List<RankItem>   ← rank, point, name, id, anonymous
    └── List<DisplayName>    ← canonical name per player id
```

### Supported races (`RaceConfig`)

`slimerace`, `fishing`, `battle_pencil`, `battle_trinity`, `daifugo`, `daifugom`, `casinoraid` (4 subraces: poker, slot, roulette, bingo)
