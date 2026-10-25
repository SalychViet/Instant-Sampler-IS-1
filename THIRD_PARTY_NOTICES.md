# Third-party components / Сторонние компоненты

IS-1 is licensed under the GNU General Public License v3.0 or later (see `LICENSE`).
It includes or links the following third-party components. All of them are compatible with GPL-3.0-or-later.

IS-1 распространяется по лицензии GNU GPL v3.0 или более поздней (см. `LICENSE`).
В приложение входят следующие сторонние компоненты; все они совместимы с GPL-3.0-or-later.

| Component | Version | License | Source | Where in this repo |
|---|---|---|---|---|
| Ableton Link | bundled | GPL-2.0-or-later | https://github.com/Ableton/link | `third_party/link` (license: `third_party/link/LICENSE.md`, `GNU-GPL-v2.0.md`) |
| Asio (standalone), used by Link | bundled | Boost Software License 1.0 | https://github.com/chriskohlhoff/asio | `third_party/link/modules/asio-standalone` (license: `asio/LICENSE_1_0.txt`) |
| LAME (MP3 encoder) | 3.100, unmodified | LGPL-2.0-or-later | https://lame.sourceforge.io | `third_party/lame` (license: `third_party/lame/COPYING`) |
| Oboe | 1.9.0 | Apache-2.0 | https://github.com/google/oboe | Gradle dependency `com.google.oboe:oboe` |
| AndroidX (core-ktx, activity-compose, lifecycle) | see `app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack/androidx | Gradle dependencies |
| Jetpack Compose (ui, foundation, material3) | BOM 2024.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose | Gradle dependencies |
| Kotlin standard library | 2.0.20 | Apache-2.0 | https://kotlinlang.org | Gradle dependency (via Kotlin plugin) |

## Notes / Примечания

- **LAME**: this product uses the LAME MP3 encoder (https://lame.sourceforge.io). LAME is used unmodified.
  If you modify LAME, the modifications must be released under the LGPL.
  Это приложение использует MP3-кодер LAME. Код LAME не изменён.
- **Ableton Link**: "Ableton Link" and the Link logo are trademarks of Ableton AG. IS-1 is not affiliated
  with or endorsed by Ableton. Link is used here under the GPL-2.0-or-later option of its dual license.
  «Ableton Link» и логотип Link — товарные знаки Ableton AG. IS-1 не связан с Ableton и не одобрен ею.
- **Apache-2.0 components** (Oboe, AndroidX, Compose, Kotlin): Apache-2.0 is compatible with GPLv3
  (but not with GPLv2-only), which is one reason IS-1 is licensed under GPLv3 or later.
  The full Apache-2.0 text: https://www.apache.org/licenses/LICENSE-2.0
