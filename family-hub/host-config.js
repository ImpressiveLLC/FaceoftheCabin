// Checked-in default — overwritten at Docker build time (see Dockerfile's
// RUN step) with the real GOOGLE_CLIENT_ID / ADMIN_EMAILS for whatever host
// this image was built for. Exists in git so this file is never a 404
// outside the built image either (local static-file serving, the
// Playwright test suite, opening family-hub.html directly) — empty values
// here just mean "no host-provided value," which family-hub.html already
// handles for googleClientId and now handles for adminEmails too.
window.HOST_CONFIG = { googleClientId: '', adminEmails: '' };
