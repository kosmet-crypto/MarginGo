# MarginGo

Hobby resale tracker (Finn.no first; a Trips tab for buying on one market and selling on another, e.g. KupujemProdajem → Finn). Single-file web app wrapped in a small Android WebView app. All data stays on the device.

## Working with the owner
- The owner writes in Serbian (Cyrillic). Answer in Serbian Cyrillic; code, comments and commit messages stay in English.
- New ideas are discussed first. Be critical and say when something is impractical or not possible. Don't write code for a new feature until the owner explicitly says to start (e.g. „сад крени да пишеш код“ or „крени да радиш“). Bug fixes the owner reports can be made directly.
- The owner is often on a phone: keep steps short, avoid anything that needs copying long strings, and prefer doing the work yourself.
- Keep it simple. It is a hobby tool, not a business app.

## Layout
- `index.html` — the whole app (HTML, CSS, JS, translations). No build step, no dependencies.
- `sw.js`, `manifest.json`, `icons/` — PWA for the browser version (GitHub Pages).
- `android/` — WebView wrapper (Java, package `app.margingo`):
  - `MainActivity.java` — hosts the page at `https://appassets.androidplatform.net/assets/www/index.html`, JS bridge `window.MarginGoAndroid`, file save/open, share intent, update checks.
  - `WebUpdater.java` — over-the-air page updates: downloads `index.html` from `main`, falls back to the bundled copy if it doesn't call `ready()`.
  - `ApkInstaller.java` — downloads the latest release APK and installs it in-app.
  - `Txt.java` — native strings in sr/nb/en by phone language.
  - Flavors: `sideload` (GitHub Releases, self-updating) and `play` (no self-updates, no `REQUEST_INSTALL_PACKAGES`; for Google Play later). `BuildConfig.SELF_UPDATE` switches the behaviour.
- `.github/workflows/android.yml` — builds `margingo.apk` (sideload) and `margingo-play.aab` on every push; on `main` it publishes a GitHub Release tagged `v1.0.<run_number>` (= versionCode).

## Signing key — do not touch
- The release key is `android/app/margingo-release.jks`, uploaded by the owner. Its password is only in the repo secret `MG_KEYSTORE_PASSWORD`.
- Never replace, regenerate, move or delete the keystore, and never print or commit its password. Android only installs updates signed with the same key; changing it forces every user to reinstall and lose data.

## Data model (localStorage key `margingo`)
- `items[]`: `name, cat, tripId, status (watch|bought|transit|listed|sold|writeoff), qty, buyPrice, buyCur, buyRate, estSell, sellCur, costs (kr, total), link, note, created, bought, sales[]`.
  - `sales[]`: `d, qty, price, cur, rate, nok, fee, w` (`w` = write-off, price 0).
  - `buyRate` (kr per unit of `buyCur`) is frozen when the item leaves `watch`; watched items use today's rate.
- `trips[]`: `name, from, to, buyCur, sellCur, cost, d1, d2`.
- `ledger[]`: `t` = `capIn | capOut | withdraw | reinvest`, `amt` (kr), `p` (purpose id), `note`, `d`.
- `cats[]`, `purposes[]`: built-ins have a translation `key`; renamed or user-added ones have `name`. Purpose `reinvest` is fixed.
- Money: profit per sale = `nok − qty × (unit cost + costs/qty) − fee`. % = profit ÷ what was paid. Cash box = all sale profits − withdrawals − reinvested. Available capital = capIn − capOut + reinvest − stock value. "Total earned" (sum of profits) is never reset.
- Keep backups compatible: add fields with defaults in `normalize()`, never rename or drop existing ones. Backups are the same JSON with `app: "MarginGo"`.

## Conventions
- Every UI string goes into all three dictionaries in `I18N` (`sr`, `en`, `nb`); Serbian is the default language.
- Amounts are shown in kr (`kr()`); other currencies via `money()`. Parse user input with `num()` (accepts comma decimals).
- Page ↔ app contract: when the page starts calling a new `MarginGoAndroid` method, raise `<meta name="margingo-native-api">` in `index.html` and `WebUpdater.NATIVE_API` together. `WebUpdater` also requires `id="mg-app"` and a page larger than 20 KB.
- Browser version: bump `VERSION` in `sw.js` when changing `index.html` or the icons.
- No external libraries, no network calls except exchange rates (open.er-api.com, jsDelivr currency-api fallback) and update checks. Nothing that breaks Google Play rules (no scraping Finn/KP).

## Checking changes
- There is no test suite. Serve the repo root (`python3 -m http.server`) and drive `index.html` with Playwright/Chromium (preinstalled): add items, sell, check `totals()` in the console, switch languages, reload to confirm persistence. Watch for page errors.
- The Android part can't be built in the cloud container (no Android SDK); CI builds it. After pushing, check the "Android APK" workflow run.
