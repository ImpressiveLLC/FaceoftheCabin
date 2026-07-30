# Ansible — host provisioning & CI/CD runner setup

Recovery runbook: if the M920q (or a new host) needed to be rebuilt from
nothing, this is what re-establishes it — Docker, the repo clone, and a
registered GitHub Actions self-hosted runner so `git push` to `main`
auto-deploys (see `.github/workflows/deploy-family-hub.yml`).

## Why a self-hosted runner, not SSH-from-GitHub

The cabin M920q is Tailscale-only, behind Starlink CGNAT — GitHub's hosted
runners have no network path to it, and there's no public IP to open a port
on. The alternative (store an SSH private key as a GitHub secret, have the
workflow SSH in) works but means a home server's access key lives in a
third party's secret store. A self-hosted runner instead:

- Runs *on* the target host, connects *outbound* to GitHub over Tailscale.
- No inbound ports, no SSH keys leave the machine.
- Free — GitHub Actions self-hosted runners have no usage-based cost, which
  matches this project's "free/personal-tier only" constraint (see
  `CLAUDE.md`).

## One-time setup (per host)

1. Get a runner registration token: GitHub repo → **Settings → Actions →
   Runners → New self-hosted runner**. Copy the token shown (valid ~1 hour,
   single use — don't save it anywhere, just paste it into the command below).
2. From a machine with SSH access to the target host (via Tailscale):
   ```bash
   cd ansible
   ansible-playbook -i inventory.ini site.yml --limit cabin \
     -e github_runner_token=PASTE_TOKEN_HERE
   ```
   Use `--limit home` for the home machine instead.
3. Confirm it registered: GitHub repo → Settings → Actions → Runners should
   show the host online with the label `cabin-m920q` (or `home-hub`).
4. Set the `CABIN_REPO_PATH` repository variable (Settings → Secrets and
   variables → Actions → Variables) if the repo isn't cloned to
   `/opt/FaceoftheCabin` — the workflow reads this, defaulting to that path.

After this, every push to `main` that touches `family-hub/**` or
`cabin-orchestration-platform/**` deploys automatically. No further manual
SSH sessions needed for routine deploys.

## Re-running after a host rebuild

The playbook is idempotent — re-running `site.yml` against a fresh install
does the whole thing again safely: installs Docker if missing, clones the
repo if it's not there (never force-overwrites local changes if it is),
and skips runner registration entirely if `/opt/actions-runner/.runner`
already exists. If the runner needs to be *re-registered* (e.g. the host
was wiped), delete `/opt/actions-runner` first, then re-run with a fresh
token.

## What this does NOT do

- Does not touch DNS, Cloudflare Tunnel config, or Google OAuth settings —
  those are manual account-level steps documented in
  `docs/EXECUTION_PLAN_2026-07-30.md`.
- Does not deploy anything by itself — it only gets the host to a state
  where the GitHub Actions workflow *can* deploy to it. The actual first
  deploy happens on the next qualifying push after setup.
- `roles/webhosted_placeholder/` is an intentional no-op — see the comment
  in that role for why, and what to do instead if a web-hosted target is
  ever added.
