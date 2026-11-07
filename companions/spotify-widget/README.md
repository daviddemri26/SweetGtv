# Personal Spotify quick panel

The local companion opens from the music indicator or a fresh Up press at the Home header. It has no extra app-library icon. The launcher remains network-free; account API access is confined to this companion.

## Live account data

The fixed catalog resource has been removed. No bundled playlists, tracks or dated snapshot are used.

- Recent: one API page containing at most the last 48 play events, newest-first and deduplicated. Repeats can produce fewer than 48 cards; older pages are not requested to fill the result.
- Playlists: one API page containing at most 48 actual account playlists. Both catalog parsers ignore any rows beyond the first 48 and never follow the supplied next URL.
- Resume: up to 100 saved episodes examined for incomplete resume positions. This is saved episodes to resume, not a complete history of listened podcasts.
- Current playback: live track or episode, active Spotify Connect device, playing state and position. Matching API artwork remains authoritative across native play/pause changes.

Current playback refreshes every five seconds while the panel is resumed. Successful catalogs have a 60-second refresh interval and load when selecting a category or reopening the panel. Refresh requests a read-only reload. Failures back off, Spotify rate limits are respected, and retained-response timestamps remain internal. Pause cancels network work and disconnects native callbacks; no background polling service is installed.

Four arrows navigate transport, category and content rows. Back closes the device picker first, then the panel. Cards use four fixed columns, edge-to-edge 54dp covers within the existing 54dp card height, and 12/10sp copy. Small covers have rounded upper-left/lower-right corners and square upper-right/lower-left corners. The 3dp antialiased focus frame sits entirely inside each card and matches its outer 8dp rounded corners and square lower-left edge. Titles are truncated while unfocused and marquee only when focused. The viewport fits the result count up to three visible rows; rows beyond it scroll. Metadata polling and command progress preserve focused item identity and scroll.

The main cover fills a 116dp header slot, flush with the upper/left edges, with its lower-right corner rounded. The artist/podcast line contains only the artist/podcast; playback device and state remain available to accessibility. A thin progress bar shows elapsed and total time. Up from transport focuses the bar. Left/Right preview ten-second steps without seeking, including repeated presses and held keys. The white thumb continues to show actual playback while a green ring and green time show the draft. Only OK sends one seek request; leaving the bar, media/device changes and the actual track ending or restarting cancel the draft. Seeking preserves play/pause and revalidates the live item and explicit destination. Time updates locally once per second while foreground, bounded by the last API state.

Routine update/success lines are hidden in the main panel. Compact icon-and-label category buttons toggle their content on click: focusing alone changes nothing, a second click closes the active category, and clicking a different category replaces it. All categories start closed. With every category closed, the extra category bottom margin is removed and the panel bottom padding is reduced to 6dp. Refresh uses the same small frameless glyph in the main panel and device picker. Open Spotify sits at the top right above the progress bar; the compact device name and trailing device icon sit at the right of the transport row. Green glyphs show focus. Up from the progress bar focuses Open Spotify; Down returns to the bar. Actionable errors and unconfirmed playback results use a one-off toast; empty-library/sign-in messages remain inline when needed.

## Visual continuity

Artwork stays visible until a replacement bitmap is ready and crossfades over 160ms. The decoder shares in-flight requests by URL, retains a bounded cache, and removes cancelled work from its bounded queue. Cached identical artwork does not animate again. The category grid stays visible but cannot be activated while a replacement catalogue loads. New data fades through once it is ready; closing keeps the compact footer after the exit fade. Cards and device rows retain their existing views on ordinary data updates. Refresh keeps the device list and focus in place. All effects are short opacity changes, respect the animator setting, and cancel on teardown.

## Home playback label

While Home is visible, it binds a signature-protected read-only service in this companion. It reads the current Spotify player every five seconds, with the existing API backoff and token handling. Only bounded artist, title, playing state and a monotonic timestamp cross the local Messenger interface. The launcher displays artist, music icon and track title in that order, on one truncated line. Paused or empty playback hides both labels. The last confirmed pair is kept briefly during a reconnect, and valid heartbeat updates renew its freshness without replaying the fade. Reads stop at unbind; stale remote data expires after 45 seconds. No notification access, media-control permission or launcher network access is added.

## Playback destinations

Play on opens the available Spotify Connect devices. Selecting another device transfers the current session, preserving its media, position and playing/paused state. The picker stays open until the transfer is confirmed; an uncertain result leaves the previous destination selected with visible feedback and is never automatically replayed. With no readable current session, selection only records the destination for the next playback. Every later card or transport command revalidates that explicit device and includes its ID. There is no implicit active-device fallback and no native playFromUri fallback. Restricted or vanished devices produce visible feedback. Open Spotify remains a separate explicit action.

Playlist/album selections send context_uri; tracks and episodes send a canonical URI, with the saved resume position for episodes. Commands distinguish Spotify accepting a request from an observed playback result. A command to a different device can move the account's listening session and interrupt its previous device. Spotify Premium is required for player commands. Some devices do not appear in the API, and an unavailable device may require opening Spotify on it first.

## Authorization and permissions

The companion requests Internet permission and prohibits cleartext traffic. OAuth uses PKCE with a dedicated public client ID, no client secret. The one-time setup helper listens only on 127.0.0.1 and transfers credentials directly through stdin to a provider protected by Android's WRITE_SECURE_SETTINGS permission. Tokens are not written to computer files. Android Keystore encrypts tokens in app-private preferences.

Requested scopes: playlist-read-private, playlist-read-collaborative, user-read-recently-played, user-read-playback-state, user-library-read, user-read-playback-position and user-modify-playback-state. Previously provisioned read-only grants must be authorized again for playback. Automatic lifecycle, refresh, browsing and artwork paths issue no playback commands. Player writes are restricted to play/pause/next/previous/seek with exactly one non-empty destination. A separate transfer method builds a single-device body with the observed source playing state. The real TV receiver required play:true when moving a playing iPad session back to a previously paused TV; confirmation checks the actual resulting state. Successful HTTP200 text acknowledgements and204 responses are not parsed as playback objects; commands confirm via a later player-state read. Authorization replacement or panel closure cancels pending commands; API rate-limit backoff is shared across controllers.

Network origins are restricted to Spotify API/token endpoints and Spotify cover CDNs. Images use bounded responses, decoding, caches and a two-worker queue. No analytics, telemetry or exported credential reads.

## Sign-in

Register `http://127.0.0.1:8765/callback` as a redirect URI in your Spotify developer app. Use that app's public Client ID and the TV's current paired ADB endpoint.

```sh
python3 companions/spotify-widget/tools/connect_spotify.py --client-id "$SPOTIFY_CLIENT_ID" --endpoint "$TV_ADB_ENDPOINT"
```

Authorize the printed Spotify URL in a browser on the same computer. The helper transfers the authorization to the TV and verifies the provisioning status. Use `--adb` if ADB is installed outside `PATH`.

## Build and test

```sh
./launcher/gradlew -p companions/spotify-widget --no-daemon testReleaseUnitTest lintRelease assembleRelease
python3 -m unittest discover -s companions/spotify-widget/tools
```
