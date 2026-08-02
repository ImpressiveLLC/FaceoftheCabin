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

1. Get a runner registration token. Either:
   - GitHub repo → **Settings → Actions → Runners → New self-hosted
     runner** and copy the token shown, or
   - if [GitHub CLI](https://cli.github.com/) is installed and authenticated
     (`gh auth login` — this opens a device-code flow in your browser, no
     token/password ever gets typed anywhere): `gh api
     repos/ImpressiveLLC/FaceoftheCabin/actions/runners/registration-token
     -X POST --jq .token`
   Either way it's valid ~1 hour, single-use — don't save it, just use it below.
2. From a machine with SSH access to the target host (via Tailscale):
   ```bash
   cd ansible
   ansible-playbook -i inventory.ini site.yml --limit cabin \
     -e github_runner_token=PASTE_TOKEN_HERE
   ```
   Use `--limit home` for the home machine instead. This runs everything
   with `become: true` (root) *except* the runner download/extract/register
   steps, which explicitly run as the deploy user instead — `/opt` itself
   needs root just to create a directory in, so the runner installs under
   `/home/{{ ansible_user }}/actions-runner`, not `/opt`.
3. The final two steps (`svc.sh install` / `svc.sh start`) genuinely need
   root — Ansible's `become: true` handles this automatically if you pass
   `--ask-become-pass` (or have a configured become password); if running
   the equivalent commands by hand over plain SSH instead of through
   Ansible, those two specific commands need `sudo` interactively — that's
   the one point where a human has to type a password, deliberately not
   something to script around.
4. Confirm it registered: GitHub repo → Settings → Actions → Runners should
   show the host online with the label `cabin-m920q` (or `home-hub`).
5. Set the `CABIN_REPO_PATH` repository variable (Settings → Secrets and
   variables → Actions → Variables) if the repo isn't cloned to
   `/opt/FaceoftheCabin` — the workflow reads this, defaulting to that path.

After this, every push to `main` that touches `family-hub/**` or
`cabin-orchestration-platform/**` deploys automatically. No further manual
SSH sessions needed for routine deploys.

## Re-running after a host rebuild

The playbook is idempotent — re-running `site.yml` against a fresh install
does the whole thing again safely: installs Docker if missing, clones the
repo if it's not there (never force-overwrites local changes if it is),
and skips runner registration entirely if
`/home/{{ ansible_user }}/actions-runner/.runner` already exists. If the
runner needs to be *re-registered* (e.g. the host was wiped), delete that
directory first, then re-run with a fresh token.

## Secrets — Ansible Vault, one-time setup

Every credential the platform depends on (`POSTGRES_PASSWORD`,
`GRAFANA_PASSWORD`, `HA_TOKEN`, etc.) used to be a hand-maintained plaintext
`infra/.env`, generated ad hoc and rotated by SSHing in and editing it by
hand — see `docs/ontology.yaml`'s `platform_secret` entity for the full
history of why that was a problem. As of 2026-08-02 those values are
**Ansible Vault-managed**: encrypted at rest, safe to commit
(`ansible/group_vars/cabin/vault.yml` — it's genuinely fine that this file
is in git; that's the point of Vault), and templated into `.env` by the
`secrets` role instead of hand-edited.

**⚠️ Security note:** rotating `cabin-postgres`'s password is a live change
to a running database that other services depend on — `cabin-backend` and
`cabin-grafana` both need to reconnect afterward. The rotation playbook
validates this automatically (see below) and fails loudly rather than
leaving a broken connection, but don't run it casually against a host you
can't immediately follow up on if something needs a manual fix.

### One-time: create the vault password

The vault password is the one secret that genuinely can't be automated away
— it's what unlocks everything else. It must live **outside this repo**,
on every machine that needs to decrypt the vault (your dev machine, and the
M920q itself, since the scheduled rotation workflow runs there):

```bash
# Generate once, store it somewhere durable (a password manager), then:
echo 'the-vault-password' > ~/.ansible_vault_pass
chmod 600 ~/.ansible_vault_pass
```

### One-time: populate the vault

```bash
cd ansible
ansible-vault create group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass
```

Add (real values, not placeholders):
```yaml
vault_postgres_password: "..."
vault_grafana_password: "..."
vault_ha_token: ""            # blank is valid — HA integration is inert until set
vault_home_ha_token: ""
vault_camera_password: ""
```

To edit later: `ansible-vault edit group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass` — never hand-edit the encrypted file directly.

### Applying / rotating

```bash
# Template infra/.env from the current vault (initial setup, or after a manual vault edit)
ansible-playbook -i inventory.ini site.yml --limit cabin --vault-password-file ~/.ansible_vault_pass --tags secrets

# Rotate POSTGRES_PASSWORD end to end (generate, apply live, re-encrypt vault,
# re-template .env, restart dependents, validate)
ansible-playbook -i inventory.ini playbooks/rotate-secrets.yml --limit cabin --vault-password-file ~/.ansible_vault_pass
```

`GRAFANA_PASSWORD`/`HA_TOKEN` rotation isn't built yet — they need different
mechanics (Grafana's admin API, HA's own long-lived-token UI, not an `ALTER
USER`-equivalent) — still hand-rotated via `ansible-vault edit` + a manual
Grafana/HA-side change, then re-run the `site.yml --tags secrets` step above.

### Automatic rotation

`.github/workflows/rotate-secrets.yml` runs the rotation playbook monthly
on the self-hosted runner (plus a manual `workflow_dispatch` trigger for
"rotate now"). Requires Ansible and `~/.ansible_vault_pass` to already
exist **on the M920q itself** — Ansible isn't installed there yet as of
this writing (the runner user has no passwordless sudo, and this host's
Python has neither `pip` nor `ensurepip`, so bootstrapping it needs one
human-run privileged command, same one-time-root pattern as the runner's
own `svc.sh install` step):
```bash
sudo apt install -y python3-pip
pip3 install --user ansible-core
```

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
