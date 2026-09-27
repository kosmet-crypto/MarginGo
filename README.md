# MarginGo

Hobby resale tracker for Android (and the browser). Note what you could buy and what it should sell for, see which items pay best (colour-coded profit in % of what you paid), record sales, and watch the profit cash box grow. Built for Finn.no first, with a Trips tab for buying on one market and selling on another (for example KupujemProdajem → Finn) in different currencies.

Everything stays on the device (`localStorage`). No account, no server.

## Features
* Items with purchase price, expected sale price, quantity, category, optional extra costs, listing link and note.
* Status: watching → bought → in transit (trips only) → listed → sold / written off. Partial sales for quantities.
* Smart list: sort by highest %, biggest profit, newest or name; filter by status, category, local/trip; search. Colours follow thresholds you set.
* Things you already own: purchase price 0 (or a small amount); the difference goes to the cash box.
* **Cash box and capital:** buying moves capital into stock; selling returns the purchase price to capital and puts only the profit into the cash box. Empty the cash box into purposes you choose (travel, treat, savings, your own); “Back into stock” reinvests it as capital. *Total earned* is never reset.
* **Trips:** markets, currencies, trip cost and how much of the trip your profit paid for.
* Currencies: kr is the home currency. Rates download automatically at most twice a day (open.er-api.com, jsDelivr currency-api as fallback) or can be typed in. The purchase rate is frozen when an item is bought, so later rate changes do not rewrite past profit.
* Share a listing from the Finn/KP app or a browser into MarginGo: a new item opens with the name and link filled in.
* Serbian (Cyrillic, default), English and Norwegian.
* Backup and restore (one JSON file) and a CSV export of all sales.

## Android app (APK)
Every change merged into `main` builds a new APK with GitHub Actions and publishes it as a release.
Always the newest version: https://github.com/kosmet-crypto/MarginGo/releases/latest/download/margingo.apk

1. Open the link on your Android phone and download `margingo.apk`.
2. Open the file. Android asks to allow installs from your browser or file manager; allow it once.
3. Install. Newer APKs install over the old one and keep your data.

Updates:
* **Page updates (most changes):** on every start with internet, the app downloads the latest `index.html` from `main` and uses it from the next start. No APK, no taps. If a downloaded page fails to start, the app falls back to the version inside the APK.
* **APK updates (Android-side changes):** checked at most twice a day (or right away with **Check for updates** in Settings) and installed from inside the app with one confirmation.

When the page starts calling a new `MarginGoAndroid` method, raise `<meta name="margingo-native-api">` in `index.html` and `WebUpdater.NATIVE_API` in the app, so older apps keep their page until the APK is updated.

### Google Play later
The Android project has two flavors:
* `sideload` — the APK above, with self-updates.
* `play` — no self-updates and no install permission (Play forbids both); CI already builds it as `margingo-play.aab` (workflow artifact).

## Signing key
The release key is **not** in the repo. CI reads two repository secrets (Settings → Secrets and variables → Actions):
* `MG_KEYSTORE_B64` — the keystore file, base64-encoded
* `MG_KEYSTORE_PASSWORD` — its password (key alias `margingo`)

Keep a copy of the keystore and password somewhere safe (password manager). Android only installs an update signed with the same key; losing it means everyone has to uninstall and reinstall.

## Browser version
Enable GitHub Pages (Settings → Pages → `main`, root) to use it in a browser or install it as a PWA. Its data is separate from the Android app; move data with Export/Import backup.
When changing `index.html` or the icons for the browser version, bump `VERSION` in `sw.js`.

The Android project lives in `android/` (a small WebView wrapper). To build locally: `cd android && ./gradlew assembleSideloadRelease` (unsigned without the key).
