# openCapsule

An Android "dynamic island" style capsule. A small pill sits over the front camera; notifications, music,
timers and more grow out of it and shrink back when done. Written in Kotlin with plain Android views.

> Status: version 1.0, the first version. It is installed by hand (sideloaded); it is not on a store.
> openCapsule is an independent project. It is not affiliated with any other capsule or island app.

## Features

**Capsules** (each can be switched on or off in settings)

| Capsule | What it shows |
|---|---|
| Notifications | App icon, title and text; tap opens the notification |
| Calls, navigation | Ongoing call and navigation notifications |
| Media | Track, artist and cover art, with play/pause. Long-press for previous / next |
| System events | 25 phone events, each with its own switch (see below): battery, Wi-Fi, hotspot, location, Bluetooth, airplane mode, sound mode, Do Not Disturb, headphones, VPN, auto-rotate, unlock |
| Flashlight | On/off, with a button to turn it off |
| Progress | Determinate download or progress notifications, with a progress bar |
| Volume | The media volume while music plays, with a bar and down/up buttons (off by default) |
| Timer | Countdown with pause/resume and +1 min; vibrates when finished |
| Stopwatch | Counts up with pause/resume and reset; swipe it away to stop |
| Barcode | A saved QR, Code 128, Code 39, EAN-13 or UPC-A code on a white panel |

**Idle pill and expanded deck**

- A small pill over the camera cutout, 40% opaque by default. Set it to 0% and it blends into the camera,
  but it still takes touches.
- Tap it to expand a deck of cards (media, volume, timer, stopwatch, flashlight, barcode). The music card has a playback bar with elapsed and total time that moves on its own, and can be dragged to seek when the player allows it. The bar appears in the deck only, not on the small music capsule. Swipe sideways to switch cards,
  swipe up or tap to collapse. A tap anywhere outside it closes it too (a setting, on by default; the tap still reaches the app underneath). It also closes by itself after 10 seconds (adjustable): background updates such as a running timer, and touches outside it, never keep it open, but pressing a button or swiping on it starts the countdown again. Haptic ticks on open, close and switch.
- Single tap, long press, double tap, swipe left and swipe right can each be mapped to: nothing, expand, play/pause,
  next track, previous track, toggle flashlight, or show barcode. A single tap expands by default; set it to nothing
  and the pill will not open when tapped.

**Dynamic cards**

Under **Capsule, Capsules, Dynamic cards** each card of the deck has its own switch: media player, volume, timer and
stopwatch, quick settings, and barcode. The timer and stopwatch share one card, one above the other. Quick settings is
a grid of tiles for the flashlight, Wi-Fi, Bluetooth, hotspot, airplane mode, Do Not Disturb, auto-rotate, location and
VPN, lit while they are on; the flashlight switches on the tile, and the others open their Settings page (Android does
not let an app change them). **Clipboard** lists your last copies as numbered lines: tap one to paste it into the open text field, hold to remove it
(it needs the accessibility service). **Notes** lists your latest quick notes: tap one to edit it, or press New note.
**Calendar** shows what is on now or next (with a Join button for Meet, Zoom, Teams and Webex links) and the next
few events; it asks for calendar access once and only reads. **Battery** shows level, charging state, time to full,
temperature and health. **Quick launcher** is a grid of up to 9 apps you pick (Dynamic cards, Quick launcher apps).
**Pomodoro** runs 25 minute focus parts with 5 minute breaks and a 15 minute break every fourth round, with a buzz
and a brief capsule when a part ends. **Phone stats** shows memory, storage, live network speed, processor cores and
clock, heat and uptime; Android does not let an app read the processor's load, so there is no CPU percentage. Battery,
stats and Pomodoro redraw by themselves while the deck is open.
The other cards appear only while they have something to show (a song, music playing for
the volume card, a running timer or stopwatch, a saved barcode). Its **Order** section moves each card up or down to set
the order you swipe through them. The same page picks the gesture that opens a *live* card.
A card is live while music plays, a timer or stopwatch runs, the torch is on, or the volume is in use. While one is
live, the chosen gesture (single tap by default) opens the deck on that card instead of running its own action; every
other gesture keeps its mapping, and the chosen one behaves normally when nothing is live. Start a stopwatch from
**Capsule, Tools** or with a broadcast (below).

**Notification filters**

Under **Capsule, Capsules, Notification filters** you choose which notifications may show a capsule:

- **By kind:** *Silent notifications* (messages the app marked low importance, so they make no sound) and *Ongoing
  notifications* (downloads and navigation). Both are on by default. The silent filter only holds back messages, since a
  download's progress is nearly always "silent"; calls always show whatever the ongoing filter says.
- **By app:** every app that can be launched is listed with a switch, with a search box (a phone easily has over a
  hundred). *Apps you have not chosen* is either **Allow all** or **Block all**, so the same list is a block list
  (allow all, switch a few off) or an allow list (block all, switch a few on). An app you have switched yourself stays
  as you set it when you change that default.

Listing the apps uses a `<queries>` entry for launcher apps in the manifest, not the broad "see all apps" permission.

**Capsule colors for light and dark**

Under **Customize, Colors** the capsule has one colour for the phone's dark theme and one for its light theme, and it
uses whichever the phone is in (the pill changes the moment the phone switches). Each row has 11 presets, five of them
light, and a rainbow dot at the end for a colour of your own: a picker with hue, saturation and brightness sliders and a
hex field, over a preview of the capsule's text. Your custom colour is kept when you pick a preset, so it can be chosen
again. On light colours the text, icons, bars and buttons turn dark; the glass, glossy and other styles keep their
material but use that dark text on a light capsule. Both default to black, as before. Album-art colours for music still
win while that switch is on.

**Text and border colors**

Under **Customize, Colors** the text on the capsule is automatic by default: light or dark, to suit the capsule's colour.
Switch *Automatic text color* off to choose your own from seven presets or a custom colour. It colours the text,
icons, bars, dots and buttons, in the brief capsules and in the expanded view alike. The icon on a capsule follows it too when it
is a plain glyph (a system event, the torch, a timer and so on); app icons, avatars and album art keep their own colours. The border (under **Shape and
border**) has a custom colour as well as its five presets. The custom picker for text previews your colour on the
capsule's own colour. Rows of swatches and style tiles wrap onto more lines instead of scrolling sideways, so nothing runs
past the edge of its card.

**Deck pages**

Related cards share one page, so the deck has fewer pages to swipe through. A page with tabs shows one card at a time and
you tap its name at the top: *Settings* and *Apps* (quick settings and the launcher), *Focus* and *Timers* (Pomodoro, the
timer and the stopwatch), *Clipboard*, *Notes* and *Calendar*, and *Battery* and *Phone* (battery and phone stats). A song
and its volume sit one above the other on one page. A page shows only the cards that are on and have something to show,
and a card whose page-mates are off stays on a page of its own. The switches and order under Dynamic cards still apply to
each card; a page sits where its first card is in that order.

**Deck look**

The expanded deck is a rounder shell (28 dp unless the shape is Square) with the same layout on every card: a round icon, the
title and a small monospace line under it, left aligned. Quick settings tiles that are on get a faint outline, bars have
round ends, and the page dots show the card you are on as a longer pill. Buttons sit in round washes; the main one (play or
pause on a song, pause or resume on a timer) is a larger filled circle. A running timer or focus session draws a progress
ring with the time inside it, and a song shows its cover as a wide panel above the title.

**Deck colors**

Under **Customize, Expanded view, Deck colors**, *Own colors for the deck* gives the expanded deck its own background and
text, apart from the capsule's. Each of the phone's two themes has its own background (the 11 presets or a custom color)
and its own text color (7 presets or a custom color), and *Automatic deck text color* picks light or dark text to suit the
background. The icons, bars, dots and buttons always take the text color, and the tiles, rows and chips are washes of that
same color, so a deck stays in one color family. It is off by default, and then the deck wears the capsule's colors as
before. *Same height for every card* keeps all the cards one size.

**Notification look**

Under **Capsule, Capsules, Notification look**:

- **Icon shape:** rounded (the default), circle, squircle, square or hexagon, for the app icon or avatar on a notification
  capsule.
- **Conversation layout:** a chat notification shows who wrote and their newest message, with the sender's avatar (a group
  chat shows "Name · Group"). Other notifications that carry a large icon show it as an avatar.
- **Seen and Reply buttons:** the buttons a chat notification offers sit along the bottom of its capsule. Seen runs the
  notification's own mark-as-read. Reply opens a small box over whatever is on screen (the capsule's window cannot take
  the keyboard), and sends your text through the notification's own reply.
- **Send a test message** posts a chat notification from this app (Android 13 and later ask for permission to post it)
  with working Seen and Reply buttons, to try the look without waiting for a real message.

**Position, the editor and screen profiles**

Under **Customize, Position**, *Move the capsule* opens a full-screen editor with a sample capsule drawn at its real size,
so wherever you drop it is exactly where capsules appear. Drag it anywhere (it holds in the middle when you get close, and
stays out of the status bar and above the expanded view's top), and size the idle pill with the slider for phones whose
camera cutout is small (50% to 200%). *Side of the screen*, *Distance from the top* and *Idle pill size* on the page edit the
same thing.

What you set belongs to the kind of screen the phone is in: portrait, landscape, and each of those on a large screen (a
tablet, or a foldable unfolded, from 600 dp wide). A screen you have not set follows the older settings that applied to
every screen, so nothing moves until you move it. **Screen profiles** lists the four with what each holds, and *Reset this
screen's position* takes the current one back. The pill follows the phone when it turns, and is left out altogether while the phone is in landscape (a pill over a camera
that is no longer at the top is rarely useful). *Hide the pill in landscape* under **Customize, Idle pill** turns that off.
Capsules still appear in landscape either way.

**The App tab**

- **Theme:** System, Light or Dark for this app's screens. It shows in the Material You style, which follows it; the other
  styles keep their own fixed look. The capsule itself follows the *phone's* light or dark theme (that is what its two
  colours are for), not this setting.
- **Font:** Default, Serif, Monospace, Condensed or Casual, each written in its own font on the chips. These are the
  families every Android phone has, so nothing is bundled or downloaded. It changes this app's screens; the capsule keeps
  its own type.
- **Language:** a row that opens Android's per-app language page. English is the only language so far, because the app's
  text is written in the code and has not been translated; this is the groundwork (the app declares its languages), not
  translations.
- **Battery optimisation:** a card under **Capsule, Setup**, "Allowed to run in the background", warns while Android may
  stop the app in the background (it can stop the notification listener, and then capsules silently stop), with an
  **Open** button to the system page. It uses the settings list, so the app asks for no extra permission.
- **Tips** and **What's new**: short, plain lists, opened from Help and About.

**Animation**

Under **Customize, Animation**:

- **Expand style:** Fluid (the default), Smooth, Snappy, Jelly or Fade. Each tile draws its own motion curve, with a
  bounce showing as the line rising past the dashed one, and choosing one plays it on a sample capsule. Smooth and Fade
  never overshoot. Fade does not grow out of the pill: it fades in and out where it rests.
- **Animation duration:** 50% to 200% of normal, which stretches the same curve.
- **Bounce:** none, low, normal or high, for the springy styles.
- **Reduce animations:** capsules fade in place in a fraction of a second with no travel, growth or bounce, and the app
  stops its own moving effects (the splash, the sections rising in, the page fades and the drifting glass background).
  It overrides the settings above.

**Motion**

Every state change is a morph, not a fade or a pop. A capsule grows out of the idle pill as one continuous shape
(its size, corner radius and position flow from the pill's) with a soft spring overshoot, and flows back into the
pill when it closes. Switching deck cards cross-fades and slides, and a capsule that changes size (for example a
long-press expand, or one capsule replacing another) morphs from its old size to its new one.
**Theme**

Capsule color, album-art colors for media, shape (pill / rounded / square), border width and color, shadow,
left / center / right position and distance from the top (from the screen edge down to the expanded view), display time, idle pill opacity, the gap between the pill and the expanded view (5% of the screen height by default), and whether every deck card has the same height. **Reset all settings** in the app puts everything back to its default (your saved barcode is kept).

**System events**

Open **Capsule, Capsules, System events** for a screen of switches grouped like this. A master switch turns them all
off at once. Charging started, charging stopped and low battery are on by default; every other event starts off.

| Group | Events |
|---|---|
| Battery | Charging started, charging stopped, low battery (shows at 5, 10, 15 or 20%) |
| Wi-Fi | Turned on, turned off, connected to a network, disconnected |
| Hotspot | Turned on, turned off |
| Location | Turned on, turned off (location services; needs no location permission) |
| Bluetooth | Turned on, turned off |
| Airplane mode | On, off |
| Sound | Sound mode (ring, vibrate, silent), Do Not Disturb on, Do Not Disturb off |
| Headphones | Connected, disconnected (wired, USB and Bluetooth, with no Bluetooth permission needed) |
| VPN | Connected, disconnected |
| Display | Auto-rotate on, off |
| Other | Device unlocked |

Each event is a **banner** that stays on screen until you swipe it up, so a change is not missed (charging and unlocking
are only a passing note, and the flashlight's banner stays while the torch is on, with its Turn off button). A
**Settings** button, and a tap on the banner, open the settings page for what changed: Wi-Fi, the hotspot's own page,
Bluetooth, airplane mode, sound, VPN, display or battery. Android does not let an app switch Wi-Fi, Bluetooth, the
hotspot or airplane mode on or off itself, which is why these link to the page where you can. Two switches at the top of
the System events page turn the staying-until-swiped and the Settings button off. While a banner is up, the lowest
priority capsules (music changes, for one) wait behind it; a notification or a call replaces it.

**Turning something off closes its banner.** A banner that stays until swiped is for something being switched on or
connecting. When the same thing is switched off or disconnects (Wi-Fi, hotspot, Bluetooth, location, airplane mode, Do Not
Disturb, headphones, VPN, auto-rotate), the banner swipes itself up and the next one waiting takes its place. If that
turn-off event has its own switch on, it shows as a short capsule that closes by itself instead of a banner that stays;
if the switch is off, the old banner just leaves without anything replacing it.

**Several banners at once.** When more than one banner is waiting (the ones that stay until swiped up: system events and
the torch), the first to arrive is shown full size in the centre. The others wait at the sides as small round icons in
the capsule's own material: the second on the right, the third on the left, the fourth on the right again, and so on,
up to five banners in all (the oldest waiting one is dropped past that). Pressing a side icon swaps it with the centre
banner, which shrinks into that same side slot. Swiping the centre banner up dismisses it and the next one in order takes
the centre. A newer banner about the same thing (Wi-Fi coming and going, say) replaces the older one in place, and a
banner that is no longer true (the torch turned off) drops out. If a call or notification takes the screen, the waiting
banners come back when it is gone.

Each source remembers the last state it saw and only reports real changes. Turning an event on shows a sample of it.
The permissions it needs for these are `ACCESS_NETWORK_STATE` (Wi-Fi and VPN) and `ACCESS_WIFI_STATE` (without it Android does not tell the app when the hotspot changes). Neither asks you anything.

**Settings layout**

The settings are split into three tabs behind a floating bar at the bottom. Each tab is a list of titled sections, and
each section is one surface of rows with an icon, a title and a hint:

| Tab | Sections |
|---|---|
| Capsule | Setup (permissions); Media and tools, Apps and calls, and Cards and phone events (which capsules appear, with the sub-pages beside them); Start a tool (timer, stopwatch, barcode) |
| Customize | Colors, Shape and border, Position, Expanded view, Idle pill, Gestures, Timing and feedback |
| App | App style, Help, Reset, About |

**App style**

The settings screen and the capsule itself (idle pill, event capsules and the expanded deck) can wear one of seven
visual styles, picked from the **App style** card (each tile previews its style, and everything restyles in place):

| Style | Look |
|---|---|
| Material You | Material 3. Dynamic colour from the wallpaper on Android 12+, baseline purple before that; follows the system light/dark setting. The default |
| Glass | Frosted translucent panels with a bright edge over soft glowing colour |
| Liquid Glass | Glass 2.0: clearer, thicker glass with strong rims and edge glow, round controls, big glossy thumbs, and a slowly drifting background |
| Skeuomorphic | Stitched leather, brushed-metal panels, glossy bevelled buttons, metal-knob switches, letter-pressed text |
| Neumorphic | One soft material: things rise out of the surface or press into it |
| Clay | Puffy pastel shapes with a rounded sheen |
| Brutalist | Flat bold colour, thick black outlines, hard offset shadows, heavy capitals |

On the capsule, a style changes what it is made of: translucent glass, glossy bevelled glass, soft extruded or puffy
surfaces, or a flat slab with a thick yellow outline and yellow buttons. Material You keeps the plain flat capsule. The
capsule's colour, shape, border and shadow are still set under **Theme**, and apply on top of the style (a capsule can
only be painted inside its own shape, so drop shadows are the Theme "Shadow" setting, not part of the style).

## Requirements

- Android 8.0 (API 26) or newer. Developed and tested on Android 16.
- Permissions, granted from the app's setup screen:
  - **Display over other apps** (needed to draw capsules).
  - **Notification access** (needed to read notifications and media sessions).
  - **Accessibility service** (optional). When on, capsules draw above the status bar and over the camera.
    Without it they sit below the status bar. The service also powers the Clipboard card. Android (10 and later)
    gives the clipboard only to the app in front and tells a background app nothing about a copy, so the history is
    topped up when you open the deck: the service takes window focus for a moment with an invisible 1 px window and
    reads what you copied last. Only the latest copy since the deck was last opened is caught, and the keyboard or
    selection may flicker for an instant. When you tap an item the service types it into the text field that has
    the cursor. It receives no events and does not read the rest of the screen; the only thing it looks at is which
    field has the cursor, at the moment you tap an item. Copies are kept on the phone only (last 30, text only,
    anything an app marks as sensitive is skipped) and can be switched off or cleared under Dynamic cards.
- Sideloaded apps need **Allow restricted settings** (App info, then the menu) before Android lets you turn on
  an accessibility service.
- Note: force-stopping the app makes Android switch the accessibility service off. Turn it on again.
- Don't run it next to another capsule app (such as Material Capsule): their pills overlap over the camera, the one on top takes the taps, and a tap meant for this pill can run the other app's action (even open the notification shade). The setup card warns when it detects Material Capsule's accessibility service.

## Build

Requires JDK 17 and the Android SDK (API 36). The Gradle wrapper is included.

```bash
./gradlew assembleDebug      # or open the folder in Android Studio and sync
adb install -r app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease    # the 1.0 build: shrunk with R8, 2.6 MB
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the standard Android **debug** key, so it installs on a phone for sideloading and testing.
A build for a store needs your own release key: create a keystore, point `signingConfigs` in `app/build.gradle.kts` at it
and keep the key and its password safe, because an update has to be signed with the same key. A build signed with a
different key cannot be installed over this one; uninstall first (settings are lost).

Create `local.properties` with your SDK path if Android Studio doesn't do it for you:

```
sdk.dir=/path/to/Android/Sdk
```

## Automation

Two broadcasts let other apps, widgets or `adb` drive the capsule. Both are exported, so any app on the
device can send them.

```bash
# Perform a gesture: TAP (open the deck), NEXT, PREVIOUS, CLOSE (while the deck is open),
# or SINGLE_TAP, LONG_PRESS, DOUBLE_TAP, SWIPE_LEFT, SWIPE_RIGHT (runs the action mapped to it, or opens a live card)
adb shell am broadcast -a dev.tushar.mycapsule.GESTURE -p dev.tushar.mycapsule --es name TAP

# Start a 90 second timer
adb shell am broadcast -a dev.tushar.mycapsule.START_TIMER -p dev.tushar.mycapsule --ei seconds 90

# Start a stopwatch (starting it again begins from zero)
adb shell am broadcast -a dev.tushar.mycapsule.START_STOPWATCH -p dev.tushar.mycapsule

# Show the saved barcode
adb shell am broadcast -a dev.tushar.mycapsule.SHOW_BARCODE -p dev.tushar.mycapsule
```

## How it works

Every feature is a `CapsuleSource` that only produces `CapsuleEvent`s. One overlay draws them all.

```
CapsuleSource â”€â”€emitâ”€â”€â–¶ CapsuleService â”€â”€â–¶ CapsuleOverlay â”€â”€â–¶ OverlayHost (window layer)
 (notifications, media,    gate: enabled?      idle / event / deck      accessibility overlay,
  charging, flashlight,    gestures â†’ actions  states, one view         or app overlay fallback
  timer, barcode)                              at a time
```

- `CapsuleEvent` and `Kind` are the single model. Adding a `Kind` adds its settings switch automatically.
- `CapsuleOverlay` is the only class that draws. It handles three states: idle (the `IdlePill`), a temporary
  event capsule, and the expanded deck.
- `ReceiverSource` is the shared base for broadcast-driven sources (charging, timer, barcode, gestures).
- `Prefs` stores settings; theme choices are enums in `CapsuleTheme.kt` and are listed in the settings
  screen by one generic helper.
- `OverlayHost` picks the accessibility overlay when `OverlayHostService` is connected, else a normal overlay.

```
app/src/main/java/dev/tushar/mycapsule/
  CapsuleEvent.kt        event model, Kind, CapsuleAction
  CapsuleSource.kt       source interface, notifications, charging, ReceiverSource
  MediaSource.kt         media sessions and the player card
  FlashlightSource.kt    torch state and toggle
  TimerSource.kt         countdown timer
  BarcodeSource.kt       saved barcode (Barcode.kt encodes it with ZXing)
  CapsuleOverlay.kt      all drawing and capsule gestures
  Morph.kt               the morph animation (spring interpolator, animated clip)
  IdlePill.kt            the pill over the camera
  OverlayHost.kt         window layer selection
  OverlayHostService.kt  optional accessibility service
  CapsuleService.kt      notification listener that wires it together
  MainActivity.kt        setup and settings screen
  Skin.kt, Skins.kt      the settings screen's visual styles (one Skin per style, drawn by FnDrawable)
  CapsuleLooks.kt        what the capsule is made of in each style (body, text and button colours)
  DynamicCards.kt        the cards of the deck that can be switched on or off
  CardSources.kt         the volume and stopwatch sources
  SystemEvents.kt        the list of phone events and their groups
  SystemSources.kt       the sources that notice them (charger, battery, network, audio, rotation, broadcasts)
  NotificationLook.kt    icon shapes, reading chat notifications, the Seen and Reply buttons
  ReplyActivity.kt       the box the Reply button opens
  TestMessage.kt         the test chat notification and its buttons
  ScreenProfile.kt       the four kinds of screen and the position kept for each
  PositionEditorActivity.kt  the drag-to-position editor
  AppExtras.kt           app theme and font, and the Tips and What's new text
  Motion.kt              expand styles, bounce and reduce, worked out into springs
  CurveView.kt           the little motion curves on the style tiles
  Splash.kt              the in-app splash animation
  Prefs.kt, CapsuleTheme.kt, Gesture.kt, CoverColor.kt
```

## Known gaps

- The app holds one saved barcode.
- Long press, double tap and sideways swipes on the idle pill were tested with the broadcast trigger,
  not by hand.
- Only tested on one phone (a camera cutout centred on the top edge).
- No automated tests yet.
- English only: the app's text is written in the code and is not translated yet, so the Language row has nothing to offer.
- Not yet tried on a real phone: the Wi-Fi, hotspot, airplane mode, VPN, headphones, sound mode and unlock events, the
  silent and ongoing notification filters, and the position editor in landscape and on large screens. Their code follows
  the same paths as the ones that were.
- The Seen and Reply buttons were tried on this app's own test message, not on a real chat app.
- The 1.0 file is signed with the debug key (see Build).

## Dependencies

- [ZXing core](https://github.com/zxing/zxing) (Apache 2.0) for barcode encoding.
- [Material Symbols](https://github.com/google/material-design-icons) (Rounded, Apache 2.0) for every icon, as vector
  drawables (`res/drawable/ic_*.xml`).
- AndroidX Core KTX and [Material Components](https://github.com/material-components/material-components-android) (Apache 2.0) for the settings screen.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

The libraries and icons it uses carry their own Apache 2.0 licences (see Dependencies above).
