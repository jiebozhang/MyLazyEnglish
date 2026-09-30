# Sanitized repository publication

This branch publishes the current household app source snapshot from local
commit `ee7e1b6`. It is based directly on the existing remote baseline rather
than the private development chain. Full development history remains local.

The publication excludes local environment files, credentials, signing keys,
APK/build outputs, raw build logs, private device screenshots, device identifiers and machine
paths. Device screenshots referenced by historical ADRs are intentionally
retained only in the private local checkout; those links are unavailable in
this published snapshot. Generated icon previews remain available.

The end-to-end test uses a synthetic PIN unrelated to the household PIN. The
compatible-service URL test uses a fictitious workspace hostname with the same
path shape. Production behavior is otherwise unchanged from the validated
source snapshot. Existing unit tests, lint and the signed Release build passed
on the local source before these publication-only sanitizations.

Release signing requires local environment configuration; secrets and APKs are
not distributed through this Git branch. Do not push the private development
branches or use `git push --all`/`--mirror` to publish them.
