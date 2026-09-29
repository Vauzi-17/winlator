# Licensing and third-party notices

This fork of Winlator is distributed under the **GNU General Public License v3.0** (`LICENSE`).
It became GPL-3.0 when LSFG Native frame generation was added, because that code is GPL-3.0.
Every component keeps its own copyright and license. The ones this fork adds are listed below.

## Winlator (MIT)

Upstream Winlator is **MIT © 2023 BrunoSX**. Its notice is kept in full in `LICENSE.MIT`, as the
MIT license requires. Code taken under MIT stays available under MIT. The combined work is GPL-3.0.

## LSFG Native frame generation (GPL-3.0-or-later)

`app/src/main/cpp/winlator/lsfg/` is copied from
[Bannerlator](https://github.com/The412Banner/Bannerlator) by **The412Banner** (GPL-3.0), at commit
`b12b53656d3e490f8b89f6330bc54272110e19e9`.

Bannerlator ported it from [WinNative](https://github.com/WinNative-Emu/WinNative)
(GPL-3.0-or-later). WinNative credits its LSFG port to Camille LaVey / the
Eden Emulator Project. That port follows
[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) by **PancakeTAS** (GPL-3.0).

The files carry their original `SPDX-FileCopyrightText` / `SPDX-License-Identifier` headers
(Eden Emulator Project, lsfg-vk).

Changes made in this fork:

- Vulkan dispatch comes from the app's own table (`lsfg_vktable.h`) instead of Bannerlator's
  compositor context.
- The JNI class is renamed to `com.winlator.cmod.core.LsfgNative`.
- `Engine::setGovernorEnabled` is added for the adaptive mode.
- The new DisplayX present path is in `app/src/main/cpp/winlator/renderer/framegen/`.

**No Lossless Scaling shaders are included.** The user imports `Lossless.dll` from their own copy
of [Lossless Scaling](https://store.steampowered.com/app/993090/) (by THS) in Settings. The DLL is
read as data only; it is never loaded or run. Its shaders are translated to SPIR-V on the device
and cached in the app's private storage.

## DXVK DXBC translator (zlib)

`app/src/main/cpp/thirdparty/dxbc/` is a vendored subset of [DXVK](https://github.com/doitsujin/dxvk),
© Philip Rebohle and contributors, under the zlib license (`thirdparty/dxbc/LICENSE.md`). It is the
same subset that lsfg-vk and Bannerlator use.

## Vortek (LGPL-2.1)

`app/src/main/cpp/vortekrenderer/` is taken from
[brunodev85/winlator-app](https://github.com/brunodev85/winlator-app) by **BrunoSX**, under the
GNU Lesser General Public License v2.1 (`vortekrenderer/LICENSE`).
