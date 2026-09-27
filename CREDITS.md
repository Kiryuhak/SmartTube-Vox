# Credits / prior work

This SmartTube integration is based on the **Voice Over Translation** ecosystem and Yandex browser VOT API behavior documented by these open-source projects:

| Project | Author / org | Role |
|---------|----------------|------|
| [voice-over-translation](https://github.com/ilyhalight/voice-over-translation) | [ilyhalight](https://github.com/ilyhalight) | Browser extension; UX, audio fallback and API flow reference |
| [vot-cli](https://github.com/FOSWLY/vot-cli) | [FOSWLY](https://github.com/FOSWLY) | CLI client; protobuf / request patterns |
| [vot.js](https://github.com/FOSWLY/vot.js) | [FOSWLY](https://github.com/FOSWLY) | `fail-audio-js` fallback for `AUDIO_REQUESTED` |
| [dual-vot-patches](https://github.com/sashade8-ship-it/dual-vot-patches) | [sashade8-ship-it](https://github.com/sashade8-ship-it) | Dual VoT architecture, timing, content range, audio parts, and integration |
| [revanced-patches](https://github.com/anddea/revanced-patches) | [anddea](https://github.com/anddea), [Jav1x](https://github.com/Jav1x) | Yandex VOT protobuf and API client original implementation |
| [morphe-patches-yavot](https://github.com/123jjck/morphe-patches-yavot) | [123jjck](https://github.com/123jjck) | Morphe Yandex VOT integration |
| [morphe-patches](https://github.com/MorpheApp/morphe-patches) | [MorpheApp](https://github.com/MorpheApp) | Morphe patches base |

SmartTube TV code in `common/.../vot/` is adapted and ported for Android TV (OkHttp + ExoPlayer).

Yandex VOT endpoints are **unofficial** and may change without notice.
