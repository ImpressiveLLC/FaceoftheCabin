# Ask CLI evaluation

The standard-library-only [harness](../../../scripts/ask_eval.py) calls the existing
`POST /api/helpdesk/ask` route sequentially. Python 3 is already available on M920q.
No installation of packages, production rebuild or service restart is needed.
The reviewed copy can run in an operator-owned directory outside the deployed checkout.
GitHub holds the harness, frozen prompts and reviewed report; private run files stay
outside the repository. Do not paste tokens into chat, command arguments or PRs.

## Authentication

SSH authenticates the operating-system session; Ask still enforces application
authentication. The harness has two explicit modes:

- `--token-file /private/path`: use an operator-supplied CabinSession file, owner-only
  permissions on Linux. The token is read into memory and never written to results.
- `--resident-admin-session`: only after explicit operator approval, on M920q,
  read configured administrator identities and select one unexpired, non-revoked
  administrator CabinSession through a read-only SQL transaction. The token remains
  in local process pipes/memory and is sent only to the loopback Ask API. This does
  not create a session, impersonate a newly invented account or bypass the interceptor.
  Normal `CabinSessionService.validateAndExtend` updates its rolling expiry on use.
  The current platform has no dedicated read-only Ask service principal; this reuses
  a broader existing session solely for the fixed Ask route.

The harness refuses external URLs, embedded credentials, queries, redirects and
ambient proxies. Credentials cannot be passed as arguments. It does not read
browser storage, vault contents, `.env` files or unrelated session rows. Keep host
access operator-controlled. This narrow helper uses the verified M920q container
names/database; other installations should supply their own session file through
an SSH loopback forward instead of copying instance-specific defaults.

## Running

From the installed copy on M920q, after session reuse is approved:

```sh
python3 ask_eval.py --questions cli-questions-r1.json \
  --output /home/nate/.local/share/faceofthecabin-eval/run-UNIQUE.jsonl \
  --resident-admin-session --record-runtime --repeats 3
```

Use a new output path every time. Files are owner-only and cannot overwrite an
existing file or be stored beneath a Git checkout. Results persist after each
response. `--denial-only` checks the anonymous denial path without reading any
session. Authentication/transport/contract failures stop the run immediately;
there is no blind retry after a timeout while server generation might continue.
Console progress contains question IDs, HTTP status, model/fallback and duration
only. Run output includes the question/harness hashes, runtime checkout and image
identities, model list, exact questions, sanitized answers, source metadata and
HTTP duration. Model defaults are unchanged. Manual review is required; a 200 is
never automatically graded as an answer pass.

The harness removes its own token, common credential patterns, email addresses,
private URL queries/userinfo, pointer-node details and all raw source content.
These rules are limited redaction, not a guarantee of arbitrary-secret detection.
Review the private results before publishing short excerpts or aggregate scores.
Store only sanitized evidence necessary to support findings in a PR.

## Trial accounting

[`cli-questions-r1.json`](cli-questions-r1.json) freezes 24 advisory questions from
the Q seed set, with three repeats (72 planned HTTP answer trials). Q20 is an
installation execution test and is excluded from the answer denominator. Q11 and
Q23 have explicit advisory wording; these do not execute role fixtures, failed-model
paths or injected retrieved documents. Q02 condition variants and static-only Q09
fixtures are also not installed into production. Those tests remain separate.

The earlier browser report sent Q01–Q25 once each without a byte-for-byte prompt
manifest. Its 0/25 figure is historical, single-pass answer evidence, including a
Q20 answer proxy; it does not establish installation coverage or per-question
repeatability. The CLI series is a reproducible baseline, not an exact paired
comparison to that pass. Future improvements must reuse this frozen manifest and
also add controlled local role/privacy/safety variants before shipping.

Grade factual coverage, supporting citations, and unsupported/safety claims
separately against the source answer set. State unavailable evidence explicitly.
Report pass/executed, executed/planned, model/fallback, per-question repeat results
and recurring failures. Keep build SHA, runtime image and source corpus version
separate; a host checkout alone does not prove which source built an image.

Local harness verification:

```sh
python3 -m unittest discover -s scripts -p test_ask_eval.py
```
