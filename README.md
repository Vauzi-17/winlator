<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Logo" />  
</p>

# Winlator

Winlator is an Android application that lets you to run Windows (x86_64) applications with Wine and Box86/Box64.

# Installation

1. Download and install the APK (Winlator_7.1.apk) from [GitHub Releases](https://github.com/brunodev85/winlator/releases)
2. Launch the app and wait for the installation process to finish

----

[![Play on Youtube](https://img.youtube.com/vi/8PKhmT7B3Xo/1.jpg)](https://www.youtube.com/watch?v=8PKhmT7B3Xo)
[![Play on Youtube](https://img.youtube.com/vi/9E4wnKf2OsI/2.jpg)](https://www.youtube.com/watch?v=9E4wnKf2OsI)
[![Play on Youtube](https://img.youtube.com/vi/czEn4uT3Ja8/2.jpg)](https://www.youtube.com/watch?v=czEn4uT3Ja8)
[![Play on Youtube](https://img.youtube.com/vi/eD36nxfT_Z0/2.jpg)](https://www.youtube.com/watch?v=eD36nxfT_Z0)

----

# Runtime: Bionic or Glibc

Each container can use one of two runtimes, chosen when the container is created (Container Settings -> Runtime):

- **Bionic** (default): the native Android runtime of this fork (Proton 9.0 x86_64/arm64ec, Box64/FEXCore bionic, adrenotools drivers).
- **Glibc**: the Linux side of [brunodev85/winlator](https://github.com/brunodev85/winlator) (glibc, Wine 10.10, Box64 0.4.4, Mesa Turnip + Zink). The wineprefix still lives in the container, only the Linux runtime differs. Requires an Adreno GPU (Turnip); PulseAudio is the recommended audio driver.

The glibc files are downloaded at build time from a pinned brunodev85/winlator-app commit (`downloadGlibcRuntime` Gradle task, SHA-256 verified) and installed on first use into `<app data dir>/g`.
That rootfs has its original prefix (`/data/data/com.winlator/files/rootfs`) compiled into its binaries, so on install every occurrence is rewritten in place to the real path, padded with extra slashes to keep the same length.
This keeps the glibc runtime working when the APK is renamed or cloned to another package name, as long as the package name is at most 23 characters long.

# Useful Tips

- If you are experiencing performance issues, try changing the Box86/Box64 preset in Container Settings -> Advanced Tab.
- For applications that use .NET Framework, try installing Wine Mono found in Start Menu -> System Tools.
- If some older games don't open, try adding the environment variable MESA_EXTENSION_MAX_YEAR=2003 in Container Settings -> Environment Variables.
- Try running the games using the shortcut on the Winlator home screen, there you can define individual settings for each game.
- To speed up the installers, try changing the Box86/Box64 preset to Intermediate in Container Settings -> Advanced Tab.

# Credits and Third-party apps
- Ubuntu RootFs ([Focal Fossa](https://releases.ubuntu.com/focal))
- Wine ([winehq.org](https://www.winehq.org/))
- Glibc runtime (rootfs, Box64 glibc, Turnip/Zink) from [brunodev85/winlator](https://github.com/brunodev85/winlator), GLIBC patches by [Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- PRoot ([proot-me.github.io](https://proot-me.github.io))
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- D8VK ([github.com/AlpyneDreams/d8vk](https://github.com/AlpyneDreams/d8vk))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))

Many thanks to [ptitSeb](https://github.com/ptitSeb) (Box86/Box64), [Danylo](https://blogs.igalia.com/dpiliaiev/tags/mesa/) (Turnip), [alexvorxx](https://github.com/alexvorxx) (Mods/Tips) and others.
Thank you to all the people who believe in this project.