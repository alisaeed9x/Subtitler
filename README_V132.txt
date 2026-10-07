V132 — Anime Witcher backend fix

This version fixes the empty Anime screen seen in V131.

Root cause fixed:
- Firestore REST fields are typed wrappers (stringValue/integerValue/booleanValue/mapValue/arrayValue); V131 was reading them as plain JSON strings.
- Home no longer depends on Algolia to load the library. It reads the original Anime Witcher `anime_list` Firestore collection first and only falls back to Algolia.
- Search keeps the original Algolia path and falls back to the same Firestore collection.
- Episode summaries and server fields now unwrap Firestore values correctly.
- Server playback still opens in Subtitler BrowserActivity, not the old Anime Witcher player.

Backend identifiers confirmed from the supplied original APK:
- Firebase project: animewitcher-1c66d
- Firestore collection: anime_list
- search_service document: Settings/search_service
- episode summary path: anime_list/{animeId}/episodes_summery/summery
- server path: anime_list/{animeId}/episodes/{episode}/servers
- server fields include direct_link and open_browser
