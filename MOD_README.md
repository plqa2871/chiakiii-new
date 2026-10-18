# Chiaki v2.2.0 Android — modded build

## Features
- **Layout editor** (stream screen -> swipe from the edge -> **Layout**): drag any control (it can't leave the screen),
  tap it to set size (30–280%) and opacity (10–100%), Reset per control. Back / "Exit edit mode" leaves edit mode.
- **Pinned analog sticks**: both sticks are now fixed 160dp controls (they no longer float to where you touch).
  Move / resize / change opacity like any other control.
- **Exact touch areas**: a button only reacts on its *visible* shape (round buttons = circle).
- **Slide between buttons**: keep the finger down, slide from ✕ to ○ -> ✕ released, ○ pressed.
- **Chords**: put two buttons side by side (touching) and press on the seam -> both are pressed.
  Distance is `CHORD_SLOP_DP` in `ButtonView.kt` (6dp).
- **Macros** (bottom bar -> **+ Macro**): a macro is a list of steps. Each step = buttons pressed at the same instant
  for *Hold* ms, released, then *Then wait* ms, then the next step. Examples:
    - turbo: one step □+△, hold 16.7, wait 16.7, **Repeat** on
    - sequence: step 1 ✕ (hold 16.7, wait 50), step 2 △ (hold 16.7) with **Repeat** off = ✕ then △ once per tap; Repeat on = loops
  Loop macros: toggle (tap start/stop) or hold mode. One-shot macros run once per tap.
  Timing runs in **native C++** (`cpp/macro-engine.cpp`): own thread, CLOCK_MONOTONIC absolute deadlines (ns),
  last 0.3 ms busy-waited, merged with the touch state in `chiaki-jni.c` and sent straight to the session.

## Changed / new files
- cpp: new `macro-engine.{h,cpp}`; changed `chiaki-jni.c`, `android/app/CMakeLists.txt`
- touchcontrols: new `TouchLayoutStore.kt`, `TouchLayoutEditor.kt`, `Macros.kt`; changed `ButtonView.kt`, `AnalogStickView.kt`, `TouchControlsFragment.kt`
- changed: `stream/StreamActivity.kt`, `session/StreamSession.kt`, `lib/Chiaki.kt`, `res/layout/fragment_controls.xml`, `res/layout/activity_stream.xml`, `android/build.gradle`
- new: `.github/workflows/android.yml`

## Build
Push to GitHub -> Actions -> **Build Android APK** -> download artifact `chiaki-mod-apk`.
