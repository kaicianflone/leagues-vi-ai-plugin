# Third-Party Attributions

This project incorporates portions of **Quest Helper** by Zoinkwiz
(<https://github.com/Zoinkwiz/quest-helper>), redistributed under the terms of
the BSD 2-Clause License reproduced below.

Ported code is identified in the source with a header comment block and
references to the originating Quest Helper source file and line numbers. The
ported files currently include:

- `src/main/java/com/leaguesai/overlay/MinimapOverlay.java` — ported from
  `src/main/java/com/questhelper/steps/overlay/DirectionArrow.java`
  (`renderMinimapArrowFromLocal`, `createMinimapDirectionArrow`,
  `drawMinimapArrow`, `drawArrow`, `drawWorldArrowHead`) and
  `src/main/java/com/questhelper/steps/tools/QuestPerspective.java`
  (`getMinimapPoint`).
- `src/main/java/com/leaguesai/overlay/ArrowOverlay.java` — ported from
  `src/main/java/com/questhelper/steps/overlay/DirectionArrow.java`
  (`drawWorldArrow`, `drawArrow`, `drawWorldArrowHead`) and
  `src/main/java/com/questhelper/steps/DetailedQuestStep.java`
  (`renderArrow` / `makeWorldArrowOverlayHint` blink cadence and tile-poly
  Z-offset).
- `src/main/java/com/leaguesai/overlay/PathOverlay.java` — ported from
  `src/main/java/com/questhelper/steps/overlay/WorldLines.java`
  (`drawLinesOnWorld`, `getWorldLines`).

---

## Quest Helper — BSD 2-Clause License

```
BSD 2-Clause License

Copyright (c) 2020, Zoinkwiz
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Individual ported files additionally preserve the original per-file copyright
headers from Quest Helper (Copyright (c) 2021, Zoinkwiz; Copyright (c) 2018,
Lotto; Copyright (c) 2019, Trevor) as required by clause 1.
