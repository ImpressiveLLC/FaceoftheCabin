# Outdoor weather baseline

Indoor humidity and temperature only mean something next to what the air outside was doing.
Once a day the backend pulls hourly airport observations (temperature, dew point, relative
humidity) for each configured location and the Sensor History chart draws them as a dashed
baseline under the indoor lines.

## How it works

- **Source:** Iowa Environmental Mesonet ASOS/AWOS download. Public, free, no key. Times are
  requested in UTC.
- **Stations:** `WEATHER_STATIONS`, as `location=PRIMARY[,FALLBACK]` entries separated by `;`.
  Default `cabin=UBE,RPD` (Cumberland Municipal, then Rice Lake Regional). **Home has no entry
  until its nearest airport is confirmed**, so Home charts show no baseline. Add for example
  `;home=<station>` to turn it on. Nothing is guessed from a location name.
- **Per day:** the first configured station with at least 12 observations that day is used;
  otherwise the best-covered one, flagged partial. Today is always partial.
- **Schedule:** daily at 03:30 (`WEATHER_CRON`) plus a catch-up on every start, so a deploy or
  an outage backfills up to `WEATHER_BACKFILL_DAYS` (60). Re-fetching is safe (primary key).
- **Table:** `outdoor_observation` in Postgres. Never deleted by this code.
- **Cold-tier log:** `/storage/archives/weather` on the M920q HDD (bind mount in
  `docker-compose.m920q.yml`): `raw/asos_<station>_<time>.csv` is every download verbatim, and
  `outdoor_<location>_<station>_<yyyy-MM>.csv` is each month rebuilt from the table (Celsius).
  **Create the folder on the host before the first start:** `mkdir -p /storage/archives/weather`.
  If the write fails the data is still in Postgres and the job logs a warning.
- **API:** `GET /api/weather/outdoor-history?location=cabin&days=30` returns daily points
  (`tempC`, `dewpointC`, `rhPct`, `sampleCount`, `station`, `partial`). Open like
  `telemetry-history`; capped at `cabin.history.max-days`. Demo policy: ALLOW.
- **Chart:** an "Outdoor baseline" checkbox (on by default) on the humidity and temperature
  charts. Humidity shows outdoor RH; temperature shows outdoor temperature and dew point. A
  missing day breaks the line. The table and CSV export get the outdoor columns.

## Reading it

Daily means are plain averages of the reports, not time-weighted, over UTC days. Dew point is
the clean comparison: if outdoor dew point is flat while indoor humidity rises, the weather did
not add the moisture. Airport readings are a regional proxy, not the cabin's own yard.

## Not done

- Home station (needs the site's nearest airport).
- Backfill beyond 60 days (raise `WEATHER_BACKFILL_DAYS` once to load more).
- Indoor dew point computed from indoor temperature plus humidity.
