# Tablet Now Playing (Chrome extension)

Sends what is playing in Chrome (Netflix, YouTube, YouTube Music, SoundCloud, any page with a video or audio
player) to the Spotify Tablet app, and lets the tablet pause, skip and seek.

1. Tablet: Settings → Playback → **Receive from the browser** on. Note the address and code it shows.
2. PC: open `chrome://extensions`, turn on **Developer mode**, **Load unpacked**, pick this folder.
3. Click the extension's icon, enter the address and code, **Save and test**.
4. Tablet: Media source **Auto** (a playing browser tab wins) or **Browser**.

Works in Edge, Brave and other Chromium browsers the same way. Everything stays on your Wi-Fi.

## What is new in 1.2

- **Netflix:** the show, season and episode (or the film's name) and the poster are sent, seeking from the tablet works
  (Netflix refuses a plain jump, so its own player is asked), and "next" moves to the next episode. Netflix's browse pages,
  which play muted previews, are ignored.
- **Any site that uses the browser's media controls** (Spotify's web player, many radio and music sites) is picked up
  even when no video or audio element can be reached, and play, pause, next, previous and seek go through the page's own
  controls first.
- Players inside open shadow DOM, a change of address on single-page sites, friendlier site names ("Netflix" instead of
  "www.netflix.com") and a list of sites to leave out.
- The extension's page shows what it currently sees, to tell a page that is not detected from a connection problem.

Needs Chrome 111 or newer (or the same in Edge and Brave). After updating, reload the extension in `chrome://extensions`
and reload the pages that were already open.
