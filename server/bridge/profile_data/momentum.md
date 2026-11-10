# Profile: Momentum

Repository data for the agent: what is Momentum's own, after the base profile. It is written from Momentum's public
sources and checked on a test Flipper running Momentum `mntm-dev`. A line ending in "(needs checking on the Flipper)"
has not been tried on a real Flipper yet: trust the screen over it.

## The desktop

- UP opens a quick-settings panel: a row of icons (restart, invert, Bluetooth, brightness, volume, settings, lock and Momentum's own). The lock is one icon in it. Look, do not change anything there unless asked.
- DOWN opens the Archive; LEFT opens the Clock (time with seconds, battery); RIGHT opens the Passport (the dolphin's stats).
- Holding RIGHT opens the battery and power stats (Back and Next on screen).
- Holding LEFT seems to lock the controls (the lock icon shows right after) (needs checking on the Flipper).
- Holding UP, DOWN or OK: not checked (needs checking on the Flipper).

## The main menu

- A grid of 3 columns. By default: Apps, RFID, Infrared, SubGHz, NFC, GPIO, iButton, U2F, Settings, Bad KB, MNTM. The labels are short: "RFID" is 125 kHz RFID, "SubGHz" is Sub-GHz, "MNTM" is Momentum's own settings app.
- UP and DOWN move by rows, LEFT and RIGHT by columns, and every direction wraps (LEFT on Apps goes to Settings).
- It can be reordered and apps added in MNTM's settings, so its order may differ: read the selected label before OK, never count presses (needs checking on the Flipper).
- Bad KB takes the place of Bad USB: it runs keyboard scripts over USB or Bluetooth. Its scripts are still in `/ext/badusb`.

## Settings

- Bluetooth, LCD and Notifications, Storage, Power, Desktop, Passport, System, Expansion Modules, Clock & Alarm, Input, About; the list wraps.
- MNTM (the main menu's entry, headed "MNTM-DEV" and a commit on a dev build) has three entries: Interface, Protocols and Misc. The main menu's apps and the asset packs are set somewhere inside them (needs checking on the Flipper).

## The Archive

- Tabs, as seen with no favorites and no iButton keys: Sub-GHz, RFID LF, NFC, Infrared, Bad KB, U2F, Apps, Search and Browser. Favorites and iButton show when they have files. Search ("Search for files") is Momentum's own.

## Built-in apps

How to drive the main menu's apps. Each opens on a list that wraps; the key paths are from the top of that list, but
look at the screen before OK. "Receives" means it only listens; the rest change something: do not run them, and do not
open a saved file's actions, unless the operator asked for it. Each starts with `flipper_app_start` by its menu name
("Sub-GHz", "NFC", "125 kHz RFID", "Infrared", "iButton", "GPIO") or, every one, by its `.fap` path.

- **Sub-GHz** (`/ext/apps/Sub-GHz/subghz.fap`): Read, Read RAW, Saved, Add Manually, Add Manually [Advanced], Frequency Analyzer, Radio Settings.
  - Read (OK on the first entry) and Read RAW receive. Saving what was read writes a file.
  - Frequency Analyzer (DOWN ×5, OK) receives: a frequency in MHz, three more frequency slots and an RSSI bar; LEFT and RIGHT are marked T- and +T.
  - Saved, then a file: Send transmits. Add Manually writes a new file. Radio Settings changes the radio's settings.
- **NFC** (`/ext/apps/NFC/nfc.fap`): Read, Extract MFC Keys, Saved, Extra Actions, Add Manually, Debug (padlock).
  - Read (OK on the first entry) receives.
  - Saved, then a file: emulate and write act on cards and readers. Extract MFC Keys works against a reader, and Extra Actions was not opened: neither without the operator's ask (needs checking on the Flipper).
- **125 kHz RFID** (`/ext/apps/RFID/lfrfid.fap`): Read, Saved, Add Manually, Extra Actions.
  - Read (OK on the first entry) receives.
  - Saved, then a file: emulate and write. Add Manually writes a new file. Extra Actions was not opened (needs checking on the Flipper).
- **Infrared** (`/ext/apps/Infrared/infrared.fap`): Universal Remotes, Learn New Remote, Saved Remotes, GPIO Settings, Debug RX (padlock).
  - Learn New Remote (DOWN, OK) receives; Debug RX receives too.
  - Universal Remotes and a saved remote's buttons transmit. GPIO Settings changes where the IR signal goes.
- **iButton** (`/ext/apps/iButton/ibutton.fap`): Read, Saved, Add Manually.
  - Read (OK on the first entry) receives.
  - Saved, then a key: emulate and write. Add Manually writes a new file.
- **GPIO** (`/ext/apps/GPIO/gpio.fap`): USB-UART Bridge, GPIO Manual Control, 5V on GPIO, I2C-Scanner, I2C-SFP. UP and DOWN only.
  - 5V on GPIO shows OFF or ON: LEFT, RIGHT or OK on it switches the 5 V pin. Never without the operator's ask.
  - USB-UART Bridge and GPIO Manual Control drive the pins; the I2C entries talk on the bus. Only when asked.
- **Bad KB** (`/ext/apps/Tools/bad_kb.fap`): opens on a file browser ("..", folders of scripts). Running a script types on the connected computer: only when asked.
- **U2F** (`/ext/apps/USB/u2f.fap`): shows "Connect to a device" with a USB picture; it acts as a security key for the computer it is plugged into. Leave it with BACK unless asked.
- **The Archive**: compiled in (desktop, DOWN). See its tabs above. OK on a file was not checked (it may run it).

Some installed apps that only receive, opened with `flipper_app_start` by path and left with BACK (to the desktop):
IR Scope (`/ext/apps/Infrared/ir_scope.fap`), a blank scope until an IR signal arrives; NFC/RFID Detector
(`/ext/apps/Tools/nfc_rfid_detector.fap`), "Touch the reader"; Spectrum Analyzer
(`/ext/apps/Sub-GHz/spectrum_analyzer.fap`), a live spectrum after a 1–2 s blank transition. Their controls are not
labelled on screen.

## Apps and their paths

- Many more apps come with the firmware, under `/ext/apps/<Category>/`; GPIO apps sit in subfolders by board, such as `GPIO/ESP`, `GPIO/NRF24`, `GPIO/Sensors` and `GPIO/FlipperHTTP`.
- The same app can be in two folders (for example in `GPIO/` and in a board's subfolder): `flipper_firmware` lists it once, with each path.
- `/ext/apps/assets/` holds the settings apps as `.fap` files (About, Bluetooth, Desktop, Passport, Power, Storage, System, MNTM, the JS Runner, Sub-GHz's Add Manually and the rest): the catalog gives each its path there, and `flipper_firmware` lists those found.
- `/ext/apps/Scripts/` holds JavaScript scripts for the JS Runner (`.js`, with an `Examples` folder), not apps: open one from the Archive or the file browser, and only when asked, since a script can do anything an app can.
- Asset packs (icons and animations) live under `/ext/asset_packs/`.
- The app catalog follows Momentum's `dev` branch and the apps it pins (a build of it reports `mntm-dev`): a release build may have other apps, so `flipper_firmware` warns when the versions differ.

## Power and reboot

- Holding BACK to power on and LEFT with BACK to reboot were not tried on Momentum (they would power it off or reboot it) (needs checking on the Flipper).
