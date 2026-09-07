# M920q CLI baseline — 2026-09-07

Status: harness installed and anonymous-denial smoke check passed. Authenticated
answer trials are pending the operator's explicit choice of session reuse or
secure session-file input. **0/72 answer trials executed; answer pass rate N/A.**
No C1/C3 completion or answer-quality improvement is claimed.

## Verified setup

- SSH reached `nates-Little-M920q` after Tailscale reauthentication.
- Deployed checkout: `cd145620bcbe7deaa30e236779ec7e1134db7880` (unchanged).
- Running backend image ID: `sha256:c21c62ced0f99f0ec6a9e68fd785ffdead831335a70022e7e5e0cf7558872602`.
  This image identity and checkout are recorded separately; no source-to-image
  build attestation was obtained.
- Python: `3.14.4`, already installed. No packages installed.
- Ollama model list: `llama3.2:3b`, model ID prefix `a80c4f17acd5`, Q4_K_M.
  Model metadata advertises context length 131072; the active usable request
  context is not established by that value. No model/settings changed.
- Published harness revision: `1ea6e8783b130fb2e5b21c14d226db5b58310cb8`, PR #36.
- Installed from that GitHub revision into
  `/home/nate/.local/share/faceofthecabin-eval/1ea6e87/`, outside the deployed Git tree.
- Harness SHA-256: `e106cf75a6adabdb7826e00b7bbb30cf119ab432562b8a7a1244a42840125e54`.
- Frozen question SHA-256: `be2c8d5a84398638e90e7e89ce9d676d7c651654b08f4ae08d70fb4387b1c184`.
- Parent directory mode `0700`, installed script and private result file `0600`.
- Normal loopback endpoint: `http://127.0.0.1:8090/api/helpdesk/ask`.
  Unauthenticated POST returned HTTP 401; installed harness denial check passed.
- Five local harness tests passed: loopback transport, redirect rejection,
  result redaction, safe error handling and active administrator selection.

## Pending answer run

Use the [CLI runbook](cli-harness.md) and [frozen prompt manifest](cli-questions-r1.json).
Run three rounds of all 24 advisory questions, then manually grade against the
source answer set with separate factual, citation and safety findings. A successful
HTTP request is not an answer pass. No session was read for the setup/denial check.
The app's normal CabinSession authentication extends session expiry on use; this
routine side effect is disclosed in the requested session-reuse approval.

Q20 clean-host execution, role variants, model-unavailable and retrieved-document
injection fixtures remain separate and unexecuted. The old browser 0/25 record
included a Q20 answer proxy and used one pass without an exact prompt manifest;
it must not be presented as a repeated test or installation evidence. This new
manifest establishes reproducibility for the next measured improvement.

No application source/configuration, knowledge records, user accounts, deployed
checkout, services or main branch were changed. The only host additions are the
reviewable standalone harness, frozen prompts and private smoke-check result.
