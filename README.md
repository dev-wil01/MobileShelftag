# MobileShelfTag

An Android app (Zebra DataWedge scanners) for looking up products offline and sending shelf-tag print requests to a companion desktop application on the local network.

- Barcode or SKU lookup from an offline SQLite copy of a product file that the desktop application provides.
- Bulk list that keeps the exact scan order.
- Prints only through the desktop application's local API. The address and access token are typed into the app's Settings screen; nothing about any network is stored in this repository.
- Screens: React + TypeScript (Vite). Device code: Kotlin.

## Build

The debug APK is built by GitHub Actions (`.github/workflows/android.yml`): run the workflow, then download the `MobileShelfTag-debug-apk` artifact. No product data, addresses, tokens or signing keys are stored in this repository. A signed release build reads its key from repository secrets (`release-apk.yml`).

## Releases and in-app updates

Every push to `main` runs the **Release** workflow: it runs the tests, builds the signed app, and publishes a GitHub Release (`v1.0.<build number>`) with `MobileShelfTag.apk` attached. The app's **Settings > Check for updates** (and a notice when the app opens) compares its own version with the latest Release and, if newer, downloads and installs it after the user confirms. Updates only install when signed with the same key, so the key is kept in repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), never in the repository.
