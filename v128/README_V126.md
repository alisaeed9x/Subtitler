# Subtitler v126 — Anime Witcher backend integration

This version does **not** create a new media server. The library tab talks to the same backend used by the old Anime Witcher APK.

## Backend recovered from the old APK

Package observed in the old APK: `com.anime.witcher`.

Firestore:
- Project: `animewitcher-1c66d`
- REST root: `https://firestore.googleapis.com/v1/projects/animewitcher-1c66d/databases/(default)/documents`
- Main collection: `anime_list`
- Episode summary: `anime_list/{animeId}/episodes_summery/summery`
- Episode servers: `anime_list/{animeId}/episodes/{001}/servers`

Search:
- Algolia host: `pm74amwqb7-dsn.algolia.net`
- Index: `series`
- Application ID: `PM74AMWQB7`
- Android client search key is embedded in `WitcherApi.kt` as it is a public client-side search credential in the old integration.

## App flow

`PrivateLibraryActivity` keeps the v125 screen and uses `WitcherApi` instead of the placeholder `/api/*` server.

Tap a series → Firestore details + episode summary → tap an episode → fetch Firestore server list → prefer a visible/non-browser/highest-quality/direct-media server → hand the resulting URL to the existing `PlayerActivity` with the same extras already used by Subtitler:

- `url`
- `ref`
- `ua`
- `title`

The subtitle button and current player/translation engine are untouched.

## Build review

Reviewed the source wiring, manifest activity registration, imports, version bump, and PlayerActivity extras by reading the code. No new backend or fake catalog is introduced in this version.


V127: روابط حلقات Anime Witcher تفتح داخل BrowserActivity المدمج في Subtitler بدل PlayerActivity/المشغل الخاص بالتطبيق القديم. لا يتم تشغيل مشغل Anime Witcher الخارجي.
