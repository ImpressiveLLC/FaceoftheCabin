#!/usr/bin/env python3
"""Download the full cabin telemetry history from cabin-backend, one calendar month per file.

Calls GET /api/events/export (administrator only) once per UTC month between --from and --to and saves
telemetry-YYYY-MM.jsonl.gz (or .csv.gz and so on, see --format) in --out-dir. Standard library only, Python 3.8+.

Resume-safe: each month is first written to a .part file and renamed only after it has been read back
completely (gzip files are decompressed to the end and their lines counted), so a finished file is always
whole. Run the same command again after an interruption and finished months are skipped; the month that was
in flight is fetched again. A month that was still in progress when it was downloaded (the current month) is
fetched again on the next run too. manifest.tsv records month, rows, bytes and sha256 of each finished file.

Credentials: an administrator CabinSession token, from the environment (CABIN_SESSION_TOKEN) or a file
(--token-file). It is never taken from the command line, so it does not land in shell history or a process list,
and it is never printed. For a Google access token use --auth-scheme Bearer.

  export CABIN_SESSION_TOKEN="$(cat ~/.cabin-token)"
  python3 scripts/export_telemetry.py --out-dir ./telemetry-export
  python3 scripts/export_telemetry.py --from 2026-08-01 --device-id z2m-temp_mech_room --field temperature,humidity
"""
import argparse
import datetime
import gzip
import hashlib
import http.client
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE_URL = "https://api.unicornpingpong.com"
CHUNK = 1024 * 1024


class FatalError(Exception):
    """Not worth retrying: bad credentials, a refused request, or a bad argument."""


def month_start(year, month):
    return datetime.datetime(year, month, 1, tzinfo=datetime.timezone.utc)


def next_month(d):
    return month_start(d.year + (d.month // 12), (d.month % 12) + 1)


def parse_day(value):
    return datetime.datetime.strptime(value, "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc)


def month_chunks(start, end):
    """(label, chunk_start, chunk_end) for every UTC month touched by [start, end), clipped to the range."""
    out = []
    cursor = month_start(start.year, start.month)
    while cursor < end:
        following = next_month(cursor)
        out.append((cursor.strftime("%Y-%m"), max(start, cursor), min(end, following), following))
        cursor = following
    return out


def read_token(args):
    if args.token_file:
        with open(args.token_file, "r", encoding="utf-8") as f:
            token = f.read().strip()
    else:
        token = os.environ.get("CABIN_SESSION_TOKEN", "").strip()
    if not token:
        raise FatalError("no token: set CABIN_SESSION_TOKEN or pass --token-file (an administrator CabinSession token)")
    return token


def request_url(args, chunk_start, chunk_end):
    params = {
        "from": chunk_start.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "to": chunk_end.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "format": args.format,
    }
    if args.device_id:
        params["deviceId"] = args.device_id
    if args.field:
        params["field"] = args.field
    return args.base_url.rstrip("/") + "/api/events/export?" + urllib.parse.urlencode(params)


def download(url, token, scheme, part_path, timeout):
    """Stream the response into part_path. Returns (bytes, sha256 hex). Raises FatalError on 4xx."""
    req = urllib.request.Request(url, headers={"Authorization": scheme + " " + token, "Accept": "*/*"})
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp, open(part_path, "wb") as out:
            while True:
                block = resp.read(CHUNK)
                if not block:
                    break
                out.write(block)
                digest.update(block)
                size += len(block)
    except urllib.error.HTTPError as e:
        if e.code == 429 or e.code >= 500:
            raise  # retryable
        if e.code in (401, 403):
            raise FatalError("HTTP %d: the server refused this credential (an administrator CabinSession token is required)" % e.code)
        raise FatalError("HTTP %d from the export endpoint: %s" % (e.code, e.read(300).decode("utf-8", "replace")))
    return size, digest.hexdigest()


def verify(part_path, fmt):
    """Read the file back completely. Returns the number of data rows, or raises ValueError if it is not whole."""
    rows = 0
    if fmt.endswith(".gz"):
        with gzip.open(part_path, "rb") as f:  # reads to the end, so a cut-off file fails here
            for _ in f:
                rows += 1
    else:
        with open(part_path, "rb") as f:
            for _ in f:
                rows += 1
    if fmt.startswith("csv"):
        rows = max(0, rows - 1)  # header line
    return rows


def is_current(path, chunk_end):
    """A finished file is reusable only if it was written after the month it covers had ended."""
    return os.path.isfile(path) and os.path.getmtime(path) >= chunk_end.timestamp()


def main(argv=None):
    p = argparse.ArgumentParser(description="Download the cabin telemetry history, one file per month, resume-safe.")
    p.add_argument("--base-url", default=os.environ.get("CABIN_API_BASE", DEFAULT_BASE_URL))
    p.add_argument("--out-dir", default="telemetry-export")
    p.add_argument("--from", dest="start", help="first day, YYYY-MM-DD (default: 4 years ago)")
    p.add_argument("--to", dest="end", help="day after the last day wanted, YYYY-MM-DD (default: tomorrow)")
    p.add_argument("--format", default="jsonl.gz", choices=["jsonl.gz", "jsonl", "csv.gz", "csv"])
    p.add_argument("--device-id", help="comma-separated exact device ids")
    p.add_argument("--field", help="comma-separated payload fields to keep (for example temperature,humidity)")
    p.add_argument("--token-file", help="file holding the token (default: environment variable CABIN_SESSION_TOKEN)")
    p.add_argument("--auth-scheme", default="CabinSession", help="Authorization scheme: CabinSession (default) or Bearer")
    p.add_argument("--retries", type=int, default=3)
    p.add_argument("--timeout", type=int, default=600, help="seconds of silence before a request is abandoned")
    args = p.parse_args(argv)

    try:
        token = read_token(args)
        today = datetime.datetime.now(datetime.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        end = parse_day(args.end) if args.end else today + datetime.timedelta(days=1)
        start = parse_day(args.start) if args.start else today.replace(year=today.year - 4, month=1, day=1)
        if start >= end:
            raise FatalError("--from must be earlier than --to")
    except (FatalError, ValueError, OSError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2

    os.makedirs(args.out_dir, exist_ok=True)
    manifest_path = os.path.join(args.out_dir, "manifest.tsv")
    if not os.path.exists(manifest_path):
        with open(manifest_path, "w", encoding="utf-8") as m:
            m.write("month\trows\tbytes\tsha256\tfile\n")

    total_rows = 0
    fetched = skipped = 0
    for label, chunk_start, chunk_end, month_end in month_chunks(start, end):
        name = "telemetry-%s.%s" % (label, args.format)
        final = os.path.join(args.out_dir, name)
        if is_current(final, month_end):
            print("%s: already downloaded, skipped" % label)
            skipped += 1
            continue
        part = final + ".part"
        url = request_url(args, chunk_start, chunk_end)
        for attempt in range(1, args.retries + 1):
            try:
                if os.path.exists(part):
                    os.remove(part)
                size, sha = download(url, token, args.auth_scheme, part, args.timeout)
                rows = verify(part, args.format)
                os.replace(part, final)
                with open(manifest_path, "a", encoding="utf-8") as m:
                    m.write("%s\t%d\t%d\t%s\t%s\n" % (label, rows, size, sha, name))
                print("%s: %d rows, %d bytes, sha256 %s" % (label, rows, size, sha[:12]))
                total_rows += rows
                fetched += 1
                break
            except FatalError as e:
                print("error: %s" % e, file=sys.stderr)
                return 1
            except (urllib.error.URLError, http.client.HTTPException, OSError, EOFError, ValueError) as e:
                print("%s: attempt %d/%d failed (%s)" % (label, attempt, args.retries, type(e).__name__), file=sys.stderr)
                if attempt == args.retries:
                    print("error: %s could not be downloaded; run the same command again to resume" % label, file=sys.stderr)
                    return 1
                time.sleep(2 ** attempt)
    print("done: %d month file(s) fetched (%d rows), %d already present, in %s" % (fetched, total_rows, skipped, args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
