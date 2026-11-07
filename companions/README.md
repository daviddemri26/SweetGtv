# Personal TV companions

These three Android TV apps are built separately from the launcher.

- [Spotify panel](spotify-widget/) provides playback controls, device selection, and live account data.
- [Continue Watching](continue-watching/) supplies the resume and YouTube rows from Android TV listings.
- [Screensaver shortcut](screensaver-shortcut/) opens the system screensaver from Home.

The [personal Home configuration](personal-home.json) enables these integrations. Save it as `config.json` in a local theme folder and apply that folder with the existing theme tool.

Use JDK 17 and Android SDK 34. Each project uses the launcher's Gradle wrapper. Build from the repository root using the command in the companion's README. Build and sign the launcher, Spotify panel, and Continue Watching with the same development key so Android grants access to their local interfaces. Spotify sign-in uses a separately configured developer app and the included setup helper.

## Personal Home options

These settings default to `false` and can be enabled in the theme.

- `personal_icon_bar` arranges the personal shortcuts, including Bluetooth, the screensaver, and stock Home, in the top bar.
- `fluid_home` scrolls the Home rows together and opens All apps as a full page under the fixed header.
- `volume_drawer` opens the volume controls from the left edge of a Home row.
- `spotify_drawer` opens the Spotify panel from the header and shows the artist and track while Spotify plays.
- `hide_home_app_names` hides the labels of the Home app tiles and keeps the labels in All apps and Continue Watching.
- `selected_apps_only` limits Home to the configured shortcuts and the apps explicitly added from the library.
- `home_continue_row` displays the Android TV resume row supplied by Continue Watching.
- `home_youtube_row` displays the separate YouTube row beneath the apps when `fluid_home` is enabled.
