# Frigate Detection — Knowledge Entries

> Added 2026-10-09 (W-39, W-5). **Not yet supplied to Ask:**
> `ReviewedDocumentSource` only reads `docs/ai-assistant/user-guide/**` (plus
> `contributing.md`) and only the sections mapped in `context-fixtures-r2.json`,
> so this file needs a reviewed-map entry and an eval run before the helpdesk
> can answer from it (same status as `heater-control.md`). Facts about the
> cabin's own cameras are as of 2026-10-09; Frigate behaviour is cited to the
> upstream docs and discussions listed at the end.

## Q: Why does the Reolink app keep notifying me about motion, but Frigate shows no events?
A: They are three separate systems. The Reolink app's alerts come from motion detection running on the camera itself. Blink alerts come from Blink's own motion detection. Frigate events come only from Frigate's **object detection** (person, car, dog and so on) running on the M920q. If object detection is off for a camera, Frigate still records it 24/7 but never creates an event, however much the camera itself reports motion. On 2026-10-09 this was the situation for `cabin_outside_reolink` (the Reolink RLC-820A, formerly `front_door`): Frigate's effective config showed `detect.enabled: False` and `objects.track: ['person']`, detection fps was 0.0, and there had been about one event since 2026-09-30.

## Q: Why was detection off for the Reolink when the other cameras detect fine?
A: When the camera was reconnected on 2026-09-30 its block in the live Frigate config was hand-written without a `detect:` section. The upstream reference config says `detect.enabled` defaults to True, but a 2026 Frigate discussion answer says detection is disabled by default, and this cabin's Frigate 0.17 effective config showed it off. Don't rely on the default: always write `detect: enabled: true` in every camera block. The other cameras in this repo set it explicitly, which is why they were unaffected.

## Q: How do I turn Frigate detection on for a camera right now?
A: Two steps. (1) Instant test, no restart: open Frigate (on the M920q, port 5000 over Tailscale), open the camera's live view, open its camera settings and switch Object Detection on. This lasts only until Frigate restarts. (2) Make it stick: Frigate → Settings → Configuration editor, find the camera block (for the Reolink: `cabin_outside_reolink`), and make sure it contains `detect:` with `enabled: true`, `width: 640`, `height: 360`, `fps: 5`; `snapshots:` with `enabled: true`; and `objects: track:` with the same labels the other camera blocks list (person, car, bicycle, motorcycle, truck, bus, bear, dog, cat; the current model doesn't support `truck`, so Frigate logs a startup warning and ignores it). Then Save & Restart. Do not change the RTSP URLs while you are there: the live file carries the working camera password.

## Q: How do I confirm Frigate detection is working?
A: Within a few minutes of enabling it: (1) Frigate → System metrics → Cameras: the camera's detection fps should be above 0 when something moves (it is 0 when nothing moves, because Frigate only runs the detector on areas with motion). (2) Frigate → Settings → Debug, pick the camera, turn on bounding boxes and motion boxes: walk into view and a labelled box should appear. (3) Frigate → Explore or Review: a new person/car item should appear. If the detection fps stays 0 while the motion boxes show movement, detection is still off.

## Q: Will a git deploy undo a fix made in the Frigate config editor?
A: Yes. The deploy workflow copies `cabin-orchestration-platform/infra/production-stack/frigate/config.yml` over the live `/storage/services/frigate/config.yml` whenever a merge touches that file, `production-stack/docker-compose.yml` or the workflow, or on a manual run. A config-editor fix is a stopgap until the same change is merged in git. For the Reolink that change is PR #127 (W-39). It must not merge until the vault value for `FRIGATE_RTSP_PASSWORD` and `infra/.env` match the camera's working password, or the deploy takes the camera dark.

## Q: Should the Reolink use HTTP-FLV or RTSP in Frigate?
A: RTSP. Frigate's camera-specific guide recommends HTTP-FLV only for Reolink cameras of 5 MP or lower; older 6 MP+ models such as the RLC-8xx series should use RTSP. The RLC-820A is 8 MP. Detect on the sub stream (`/Preview_01_sub`, 640x360, low CPU) and record on the main stream (`/h264Preview_01_main`). Frigate also recommends setting the camera to constant bit rate ("On, fluency first") and an I-frame interval of 1x the frame rate, which helps stream stability.

## Q: What can't Frigate object detection catch at the cabin?
A: Two limits worth knowing. (1) Objects the model has no label for: the model is COCO-based, so there is no "tree limb" or "deer" label. A limb falling on a car shows up as motion in the recording, not as an event; motion-retained or continuous recording is what preserves it. (2) Small or dark subjects: detection runs on the 640x360 sub stream, so a person far away at the road is only a few pixels tall, and at night the camera's IR range limits what is visible at all. Check these by scrubbing the continuous recording for known events (W-5) before shortening continuous retention (W-8).

## Q: Which Frigate detector does the cabin use, and does enabling detection cost much?
A: The CPU detector (`detectors: type: cpu`). Detector cost is per frame with motion, not per tracked label, at 5 fps per camera. Watch Frigate → System metrics → detector inference time and CPU after enabling a camera, especially while the helpdesk model is running on the same M920q.

## Sources
- Frigate configuration reference (`detect.enabled`, motion and object defaults): https://docs.frigate.video/configuration/reference
- Frigate camera configuration (roles, decoding when detection is off): https://docs.frigate.video/configuration/cameras
- Frigate camera-specific guide, Reolink section (HTTP-FLV vs RTSP by resolution, camera settings): https://docs.frigate.video/configuration/camera_specific
- Frigate discussion #22573 (cameras recording nothing until `detect: enabled: true` was added): https://github.com/blakeblackshear/frigate/discussions/22573
- Frigate discussion #11454 (HTTP-FLV not available above 5 MP): https://github.com/blakeblackshear/frigate/discussions/11454
- Frigate issue #5502 (UI toggles reset to the config file on restart): https://github.com/blakeblackshear/frigate/issues/5502
- This repo: PR #127 and backlog rows W-5, W-8, W-39
