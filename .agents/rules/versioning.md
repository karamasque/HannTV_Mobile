# Versioning & Release Rules

1. **Strict Monotonic Versioning**:
   - `versionName` uses Semantic Versioning (`X.Y.Z`).
   - `versionCode` MUST always be calculated as: `(Major * 10000) + (Minor * 100) + Patch`.
   - Examples: `1.2.5` -> `10205`, `2.1.7` -> `20107`.

2. **No Dummy High Version Codes**:
   - Local, debug, and release builds MUST use the exact same monotonic `versionCode` calculation.
   - Never use fake high numbers like `99999` for local builds, as it causes `INSTALL_FAILED_VERSION_DOWNGRADE` when updating to published releases.

3. **In-App Auto Update Compatibility**:
   - In-app updates check GitHub releases for higher `versionCode` / `versionName`.
   - Every new release tag on GitHub MUST increment `versionName` (e.g. `1.2.5` -> `1.2.6`) and `versionCode` (`10205` -> `10206`).
