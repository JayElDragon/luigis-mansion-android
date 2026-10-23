Luigi's Mansion Android port - Android Studio project (Java/UI side)
Based on the PC recompilation by rob646464: https://github.com/rob646464/luigis-mansion-recompiled
Unofficial port by MsBunnana & JayElDragon. Not affiliated with Nintendo.

WHAT'S HERE
- app/src/main/java/org/libsdl/app/
    LauncherActivity  boot menu, disc import (Game Files), menu music
    MenuView          boot menu art/buttons
    OptionsView       themed Options screen (fps 30/60/90/120, resolution, AA, VSync, audio...)
    GameSettings      writes Options into the game's settings.ini
    MyGame            game activity (120 Hz display mode, touch overlay)
    TouchOverlay      on-screen controls (virtual Xbox pad)
- app/src/main/assets/  menu art, logo, menu music, options plate + icons

NOT INCLUDED (build it yourself)
- app/src/main/jniLibs/arm64-v8a/libmain.so : the native game library. Built from the recompiled
  repo above with the Android NDK r27c (arm64-v8a, API 33), target `lmrecomp`.
  Put the built (stripped) libmain.so in that folder, then Build > Build APK(s).
- No game files. Players import their own disc image (.iso / .rvz) with "Game Files" in the app.

NOTES
- 16 KB page size: link flags are set in the native build.
- Default settings: 120 fps, render scale 1x.
