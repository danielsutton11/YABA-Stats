# yaba-stats

Collects activity stats for the YABA factions and serves them to the **YABA Faction Activity Report**
Google Sheet. Spring Boot 3.5 / Java 21 / PostgreSQL, deployed on Railway.

## What it does

- **Nightly at 01:00 UK time:** for every YABA faction, fetches the roster and then each member's Torn
  personal stats (`xantaken`, `timeplayed`, `rankedwarhits`, `revives`) for now, 30 days ago and
  90 days ago. It also fetches each member's current Elo, which comes back on the same "now" call, and an
  FFScouter battle stat estimate.
- **On demand:** the sheet can start a full run, a quick roster-only refresh, an Elo-only run for chosen
  factions, or a check of one prospective faction.
- **Rate limits:** Torn's limit is per player, so keys are grouped by the player who owns them. Each
  player gets 80 calls a minute (`RPM_PER_PLAYER`), shared by every run that's going.
- **Caching:** member stats, Elo and FFScouter estimates fetched in the last 20 hours are reused. If a run is interrupted, for example by a redeploy, the next run picks up from there.
- **Fallen players:** members whose Torn status is Fallen are skipped. They aren't fetched, aren't counted in member
  totals, and don't appear in reports. The status comes from the faction roster, so this costs no extra calls.
- **FFScouter:** saved Torn keys that are registered on ffscouter.com are found automatically and
  re-checked every 12 hours. Estimates are fetched 100 players per call. Premium keys get FFScouter's
  premium and spy data where it exists.

For 74 factions (~5,900 members), 5 keys from 5 players and an empty cache, a run takes roughly
45 minutes for stats plus a minute or two for FFScouter estimates.

## Deploy on Railway

1. **Create the project.** Push this folder to a GitHub repo and use New Project → Deploy from GitHub
   repo, or run `railway up` from this folder. Railway builds it with the included `Dockerfile`.
2. **Add a database.** Use + New → Database → PostgreSQL.
3. **Set variables on the yaba-stats service:**

   | Variable | Value |
   |---|---|
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` |
   | `YABA_API_SECRET` | a long random string; the sheet sends it with every request |
   | `YABA_KEY_SECRET` | a different long random string (16+ characters); it encrypts the stored Torn API keys |

4. **Make it reachable.** Go to Settings → Networking → Generate Domain and copy the URL.
5. **Connect the sheet.** In the Google Sheet, use YABA Stats → 2. Connect to Railway and paste the
   URL and secret. Then use 3. Add Torn API keys.

The database tables are created on first start (Flyway).

### API key encryption

Torn API keys are stored encrypted with AES-256-GCM. The encryption key comes from `YABA_KEY_SECRET`.
Each stored key also has an HMAC fingerprint, so duplicate keys can be spotted without decrypting
anything. If any keys are still in plain text (from before encryption was added), the service
encrypts them on its next start and blanks the plain-text copy.

- The service won't start without `YABA_KEY_SECRET`.
- Keep `YABA_KEY_SECRET` safe. If you change or lose it, the stored keys can't be read any more,
  and you'll need to add them again from the sheet.
- Encryption protects the database and its backups. Anyone who can open the Railway project can
  read the variable as well, so keep access to the project locked down.

### Optional variables

| Variable | Default | Meaning |
|---|---|---|
| `NIGHTLY_CRON` | `0 0 1 * * *` | Spring cron (sec min hour day month weekday) |
| `YABA_ZONE` | `Europe/London` | Time zone for the cron (follows BST/GMT) |
| `RPM_PER_PLAYER` | `80` | Torn calls per minute per player (Torn allows 100) |
| `STATS_CACHE_HOURS` | `20` | Reuse member stats and Elo fetched within this many hours |
| `FFS_CACHE_HOURS` | `20` | Reuse FFScouter estimates fetched within this many hours |
| `FFS_BATCH_SIZE` | `100` | Players per FFScouter call |
| `FFS_PER_SECOND` | `1` | FFScouter calls per second |

## API (used by the sheet)

Every `/api/**` call needs the header `X-Api-Key: <YABA_API_SECRET>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Railway health check (no key needed) |
| GET | `/api/status` | Last full run, running jobs, key counts |
| POST | `/api/keys` `{"keys":[...]}` | Add keys (merged; each is checked against Torn and FFScouter) |
| GET | `/api/keys` | Masked keys with owner, health and FFScouter status |
| PUT | `/api/factions` `[{"id":9528,"type":"Revive"}, ...]` | Replace the YABA list (sheet order kept) |
| POST | `/api/runs/yaba[?rosterOnly=true]` | Start a run now |
| POST | `/api/runs/elo` `{"factionIds":[...]}` | Fetch current Elo now for these factions' members |
| GET | `/api/runs/{id}` | Progress of a run |
| GET | `/api/report` | YABA factions and every member's 1M/3M figures, Elo and FFScouter estimate |
| GET | `/api/lookup?q=name-or-id` | Faction search |
| POST | `/api/prospects` `{"id":9528,"type":"Combat"}` | Check a prospective faction |
| GET | `/api/prospects/{id}` | Run status and members for that faction |

## Build locally

```
mvn package          # runs unit tests, builds target/yaba-stats.jar
DATABASE_URL=postgresql://user:pass@localhost:5432/yaba YABA_API_SECRET=dev java -jar target/yaba-stats.jar
```

## Notes

- FFScouter's terms say estimates belong to FFScouter. Check them before sharing the numbers widely.
- Keys that Torn rejects (wrong, disabled, paused) are taken out of use automatically. Their reason
  shows under YABA Stats → Settings → Check keys & FFScouter.
