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

From the repo root (full path if you're starting a fresh SSH session and
haven't `cd`'d anywhere yet: `cd ~/FaceoftheCabin/ansible` on the M920q, or
just `cd ansible` if you're already inside the repo checkout):

```bash
cd ansible
ansible-vault create group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass
```

**Editing the vault — two ways.** `ansible-vault create`/`edit` decrypts the
file into a temp file, opens it in whatever `$EDITOR` is set to, then
re-encrypts on save/exit. Which editor opens depends entirely on `$EDITOR`:

- **Default (no `$EDITOR` set) is `vi`/`vim`** — it's *modal*, not a normal
  text field, which trips people up who haven't used it before: press
  **`i`** first to enter Insert mode before typing or pasting anything,
  **`Esc`** to leave Insert mode when done editing, then type **`:wq`** and
  press Enter to save and re-encrypt. `Esc` then `:q!` (no save) abandons
  the edit without writing anything.
- **Easier — `nano`**, a normal type-and-arrow-keys editor with no modes.
  Set `EDITOR=nano` for just this one command (doesn't change anything
  permanently, doesn't touch any config file):
  ```bash
  EDITOR=nano ansible-vault create group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass
  ```
  Type/paste normally, then **`Ctrl+O`** (write out) → Enter to confirm the
  filename → **`Ctrl+X`** to exit. This is the recommended default for
  routine credential edits — reach for it any time the instructions below
  just say "edit the vault."

**Quoting: always wrap values in double quotes**, no exceptions —
`vault_x: "the-value"`, never `vault_x: the-value` unquoted. Passwords can
contain characters (colons, `#`, leading/trailing spaces, or a value that
happens to look like YAML's own `true`/`123`/`null`) that unquoted YAML
would silently misparse or truncate. The `"..."` you'll see below is
literal syntax to type — put the real value directly between the quotes,
there's no `<placeholder>` angle-bracket convention anywhere in this file.

Add every credential the current templates reference. Optional ones are
safe to leave as `""` — see each variable's own comment in
`group_vars/cabin/vars.yml` for exactly what stays disabled/inert while
blank, so you can judge what you actually need right now versus later:

```yaml
# Required — already-live services break without these
vault_postgres_password: "..."
vault_grafana_password: "..."
vault_camera_password: "..."
vault_blink_username: "..."       # blinkbridge -> Blink cloud account (driveway camera)
vault_blink_password: "..."

# Optional — blank is a valid, working state (see vars.yml's own comment
# on each for exactly what stays disabled while unset)
vault_ha_token: ""                # HA integration inert until set
vault_home_ha_token: ""
vault_tech_id_api_key: ""         # Tech ID Service submissions 503 until set
vault_anthropic_api_key: ""       # cabin-discovery stays local-catalog-only
vault_google_client_secret: ""    # Grafana's own Google login stays disabled
vault_cabin_alert_ntfy_topic: ""  # CRITICAL events persist, just no phone push
vault_uptime_kuma_username: ""    # blocks the not-yet-built Kuma reconciler only
vault_uptime_kuma_password: ""
vault_cloudflare_tunnel_token: "" # backup/reference only — see vars.yml's comment
vault_nodered_admin_username: ""  # Node-RED editor stays unauthenticated until set — see playbooks/enable-nodered-auth.yml
vault_nodered_admin_password: ""
```

That covers every credential this repo currently templates anywhere. If a
future change adds a new one, add it here (both to this list and to
`group_vars/cabin/vars.yml`'s indirection layer) in the same change —
that's how this checklist stays trustworthy for a from-scratch clone
instead of silently drifting out of date.

To edit later: `ansible-vault edit group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass` (add `EDITOR=nano` in front for the friendlier editor, same as above) — never hand-edit the encrypted file directly.

### Applying / rotating

```bash
# Templates BOTH infra/.env (app-level: cabin-backend/cabin-ui/family-hub)
# AND infra/production-stack/.env (the pre-existing stack: mosquitto/HA/
# Frigate/blinkbridge/etc, added 2026-08-15) from the current vault —
# initial setup, or after a manual vault edit.
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
exist **on the M920q itself** — both done as of 2026-08-02:
`ansible-core` installed via `sudo apt install -y ansible-core` (the `pip3`
route below hits PEP 668 "externally managed environment" on this host —
don't use it, it's kept only as a record of what *doesn't* work):
```bash
# doesn't work on this host, see above:
# sudo apt install -y python3-pip && pip3 install --user ansible-core
sudo apt install -y ansible-core
```

**Verified live, end to end, 2026-08-02:** ran both `site.yml --tags
secrets` (templated `.env` from the vault) and a full real
`playbooks/rotate-secrets.yml` run (generate → `ALTER USER` on the live
`cabin-postgres` → re-encrypt vault → re-template `.env` → recreate
`cabin-backend`/`cabin-grafana` → validate). Self-validated (`db: UP`),
and independently confirmed event history was untouched (`cabin-postgres`
got recreated as a side effect of `docker compose up` on its dependents —
expected and harmless, `POSTGRES_PASSWORD` only applies at first volume
init, doesn't wipe the volume). Found and fixed two real bugs only
visible by actually running it, not from reading the YAML: `playbooks/`
being one directory deeper than `ansible/roles/` broke `include_role`'s
default search path (fixed via `ansible/ansible.cfg`'s `roles_path`), and
`env.j2`'s header comment referenced a non-existent Ansible magic
variable (`inventory_hostname_group` → `group_names | first`). Also:
self-targeting the M920q via its own Tailscale hostname in
`inventory.ini` doesn't work when Ansible runs *on* the M920q itself
(hairpin — no local port-22 listener from its own perspective) — run with
`-c local -e ansible_become=false` when operating directly on the host,
plain `ansible-playbook -i inventory.ini ...` (no override) when running
from a separate machine with SSH access.

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
