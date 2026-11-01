# Third-party notices

OpenCode Mobile includes or builds with the third-party components below. Each
keeps its own license; the GPL in [LICENSE](LICENSE) does not change it.

## xterm.js and @xterm/addon-fit

Vendored in `shared/terminal-web/` (`xterm.js`, `xterm.css`, `addon-fit.js`).
<https://github.com/xtermjs/xterm.js>

```text
Copyright (c) 2017-2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
Copyright (c) 2014-2016, SourceLair Private Company (https://www.sourcelair.com)
Copyright (c) 2012-2013, Christopher Jeffrey (https://github.com/chjj/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Lucide icons

Generated into the Android and iOS icon sets from
[lucide-static](https://lucide.dev) 1.43.0. ISC License, Copyright (c) Lucide
Contributors. Portions are derived from Feather, MIT License, Copyright (c)
2013-2023 Cole Bemis.

```text
Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## File-type icons from opencode and the Material Icon Theme

Vendored in `icons/file-types/` and generated into the Android and iOS
file-type icon sets. The SVGs and the file-name lookup tables come from
[opencode](https://github.com/anomalyco/opencode) (MIT License, Copyright (c)
2025 opencode); the artwork is from the
[Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme)
(MIT License, Copyright (c) Philipp Kief).

```text
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Code color presets from editor themes

The code color values in `shared/syntax-presets/` (Nocturne, Citrus, Paper, and
Neon dark) are derived from these editor themes by
`scripts/generate-syntax-presets.mjs`, reading the theme files packaged in
[@shikijs/themes](https://github.com/shikijs/textmate-grammars-themes)
(MIT, Copyright (c) 2021 Pine Wu, Copyright (c) 2023 Anthony Fu).

| Preset | Theme | Source | Copyright |
| --- | --- | --- | --- |
| Nocturne dark | One Dark Pro | <https://github.com/Binaryify/OneDark-Pro> | Copyright (c) 2013-2022 Binaryify |
| Nocturne light | One Light | <https://github.com/akamud/vscode-theme-onelight> | Copyright (c) 2015 Mahmoud Ali |
| Citrus | Solarized Dark and Light | <https://github.com/microsoft/vscode>, palette from <https://github.com/altercation/solarized> | Copyright (c) 2015 - present Microsoft Corporation; palette Copyright (c) 2011 Ethan Schoonover |
| Paper | GitHub Dark Default and Light Default | <https://github.com/primer/github-vscode-theme> | Copyright (c) 2020 Primer |
| Neon dark | Dracula | <https://github.com/dracula/visual-studio-code> | Copyright (c) 2016 Dracula Theme |

Each is distributed under the MIT License:

```text
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries resolved by Gradle and Swift Package Manager

Android and iOS dependencies, including the Firebase SDKs, are fetched at
build time under their own licenses and are not stored in this repository.
The additional permission in [NOTICE](NOTICE) covers linking with Firebase and
Google Play services.
