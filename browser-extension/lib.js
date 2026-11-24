// Small pure helpers shared by the content script (and tested on their own). No page access in here.
(function (root) {
  const SITE_NAMES = {
    "netflix.com": "Netflix", "youtube.com": "YouTube", "music.youtube.com": "YouTube Music", "soundcloud.com": "SoundCloud",
    "bandcamp.com": "Bandcamp", "twitch.tv": "Twitch", "vimeo.com": "Vimeo", "dailymotion.com": "Dailymotion",
    "disneyplus.com": "Disney+", "primevideo.com": "Prime Video", "hulu.com": "Hulu", "max.com": "Max", "hbomax.com": "Max",
    "tv.apple.com": "Apple TV+", "music.apple.com": "Apple Music", "open.spotify.com": "Spotify", "deezer.com": "Deezer",
    "tidal.com": "Tidal", "listen.tidal.com": "Tidal", "crunchyroll.com": "Crunchyroll", "kan.org.il": "Kan",
    "mako.co.il": "Mako", "reshet.tv": "Reshet 13", "13tv.co.il": "Reshet 13", "paramountplus.com": "Paramount+",
    "peacocktv.com": "Peacock"
  };

  /** "www.netflix.com" -> "Netflix"; unknown sites keep their host without "www.". */
  function friendlySite(host) {
    let h = String(host || "").toLowerCase().replace(/^www\./, "");
    if (SITE_NAMES[h]) return SITE_NAMES[h];
    const parts = h.split(".");
    for (let i = 1; i < parts.length - 1; i++) {
      const tail = parts.slice(i).join(".");
      if (SITE_NAMES[tail]) return SITE_NAMES[tail];
    }
    return h;
  }

  const SUFFIX = /\s*[-|–—]\s*(YouTube( Music)?|SoundCloud|Bandcamp|Netflix|Twitch|Vimeo|Dailymotion|Disney\+|Prime Video|Hulu|Max|Apple TV\+?|Crunchyroll|Spotify|Deezer|Tidal)\s*$/i;

  /** Page title without "(3)" notification counts and the site's own name at the end. */
  function cleanTitle(t) {
    return String(t || "").replace(/^\(\d+\)\s*/, "").replace(SUFFIX, "").trim();
  }

  function decodeEntities(s) {
    return String(s || "")
      .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
      .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
  }

  /**
   * Netflix's title overlay: a series is [show, "S1:E2", episode name] (in separate elements), a film is just its name.
   * [parts] are the texts of the overlay's child elements, [whole] its whole text.
   */
  function parseNetflixTitle(parts, whole) {
    const p = (parts || []).map((x) => String(x || "").trim()).filter(Boolean);
    if (p.length >= 2) {
      const show = p[0];
      const label = p[1];
      const name = p.slice(2).join(" ").trim();
      return { title: name || label, artist: name ? show + " · " + label : show, series: true };
    }
    const one = (p[0] || String(whole || "").trim());
    return { title: one, artist: "", series: false };
  }

  /** The title and picture out of a Netflix title page's HTML (its Open Graph tags). */
  function parseOpenGraph(html) {
    const get = (prop) => {
      const a = new RegExp('<meta[^>]+(?:property|name)=["\']' + prop + '["\'][^>]*content=["\']([^"\']*)["\']', "i").exec(html);
      const b = new RegExp('<meta[^>]+content=["\']([^"\']*)["\'][^>]*(?:property|name)=["\']' + prop + '["\']', "i").exec(html);
      const m = a || b;
      return m ? decodeEntities(m[1]) : "";
    };
    let title = get("og:title") || get("twitter:title");
    title = title.replace(/^Watch\s+/i, "").replace(/\s*[|–—-]\s*Netflix.*$/i, "").replace(/\s+online\s*$/i, "").trim();
    return { title, image: get("og:image") || get("twitter:image") };
  }

  /** Whether the page's host is on the person's ignore list (one host or part of a host per line). */
  function isIgnored(host, list) {
    const h = String(host || "").toLowerCase();
    return String(list || "").split(/\r?\n/).map((x) => x.trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/.*$/, ""))
      .filter(Boolean).some((x) => h === x || h.endsWith("." + x.replace(/^www\./, "")) || h === x.replace(/^www\./, ""));
  }

  const api = { friendlySite, cleanTitle, decodeEntities, parseNetflixTitle, parseOpenGraph, isIgnored };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.TNP = api;
})(typeof self !== "undefined" ? self : this);
