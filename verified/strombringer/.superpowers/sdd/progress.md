# Strombringer installer-spoof (Plan 2) — ledger
Repo: StudioProjects/Strombringer (main). Plan: docs/plans/2026-07-07-installer-spoof.md
No base-Thor changes. Config over existing provider IPC.
Task 1: complete (config spoof-set, 424479d..f3cb15a). Self-verified (tiny additive transcription; build passed).
Task 2: hook (01429a9)
Task 3: wire XposedEntry (bfdb506)
Task 4: app-picker UI (b79ac31)
All spoof code done; consolidated review next; Task 5 = device.
Installer-spoof slice: code Tasks 1-4 DONE + reviewed clean (Spec ✅, Minors only). Task 5 = device verify (needs user + Play-gated test app in LSPosed scope).

Installer-spoof slice: REMOVED (2026-07-07). Device test proved the in-process spoof cannot
beat Play Integrity (server-attested from the REAL installer record). Base Thor root "reinstall
with Play" already covers that case. Reverted to clean auto-unfreeze-only Strombringer.
