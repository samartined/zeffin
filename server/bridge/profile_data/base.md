# Profile: the base (every Flipper)

Repository data for the agent: what every firmware studied shares (the official firmware, Momentum and Unleashed).
The firmware's own profile comes after this one and says what is its own. A line ending in "(needs checking on the
Flipper)" has not been tried on a real Flipper yet: trust the screen over it. Lines without it were tried on a test
Flipper running Momentum (`mntm-dev`); on another firmware, still trust the screen over them.

## Read the screen, do not count presses

- Menus can be reordered (and on some firmwares shown as a grid), and apps added or hidden: never reach an item by counting presses from memory. Look at the screen, move, look again.
- Look at the screen after each short sequence: a missed key or a dialog changes where you are.
- The desktop's animations may take a key: when a press seems lost, look again before pressing once more.

## Getting around

- UP, DOWN, LEFT and RIGHT move in menus and on virtual keyboards.
- On screens with options side by side, LEFT and RIGHT move between them (needs checking on the Flipper).
- OK opens an app or confirms the selection; BACK leaves an app or goes back one level.
- Menus and the built-in apps' lists wrap around: UP on the first item goes to the last one.

## The desktop (the idle screen)

- OK opens the main menu.
- DOWN opens the Archive, on the tab used last.
- The UP key differs by firmware: see its profile.
- LEFT and RIGHT, pressed or held, open quick access apps; which ones depends on the firmware and on Settings, Desktop (needs checking on the Flipper).
- After a while idle the controls lock (a lock icon on the desktop): OK then shows how to unlock, and BACK pressed three times unlocks them.

## The main menu and the apps opened from it

- An app opened from the main menu and left with BACK returns to the main menu with that app selected.
- An app started with `flipper_app_start`, BACK returns to the desktop, not to a menu.
- A built-in app opens on a list of entries: read it on the screen before pressing OK. Entries with a padlock are debug entries: leave them alone.

## The Archive

- Tabs, one per kind of file and Browser, changed with LEFT and RIGHT; RIGHT on the last tab wraps to the first. Empty tabs are hidden: a Favorites tab shows only when there are favorites. The firmware's profile gives the tabs it has.
- The Apps tab lists folders.
- OK on a file opens its menu (run, rename, delete and so on) (needs checking on the Flipper). It can run the file: do not press it on a signal, a key or a script unless the operator asked for that file to be used.

## The SD card (`/ext`)

- Installed apps: `/ext/apps/<category>/<appid>.fap`, where `<category>` is the app's category (it can hold a subfolder, such as `GPIO/ESP`) and `<appid>` its id. The firmware's own main-menu apps (Sub-GHz, NFC, Infrared and the rest) are `.fap` files there too.
- An app's data: `/ext/apps_data/<appid>/`.
- Saved signals and keys, by app, in these folders; the file extensions are from the firmware's sources (needs checking on the Flipper):
  - Sub-GHz: `/ext/subghz/*.sub`;
  - 125 kHz RFID: `/ext/lfrfid/*.rfid`;
  - NFC: `/ext/nfc/*.nfc`;
  - Infrared: `/ext/infrared/*.ir`;
  - iButton: `/ext/ibutton/*.ibtn`;
  - Bad USB (Bad KB) scripts: `/ext/badusb/*.txt`.
- Firmware updates go to `/ext/update/`.
- `/int` is the internal storage: small, used by the system; do not write there unless asked.

## Apps

- `flipper_firmware` lists the apps found on this Flipper, each with one line from the firmware's app catalog: what it is, its capability flags and whether it needs external hardware. `flipper_app(appid)` gives one app's whole entry: enough to find and open it, but not its controls or screens, so read the screen once it is open.
- To open an installed app, `flipper_app_start` with its `.fap` path is far faster than the menus.
- When an app opens or closes, the loader shows a transition frame for 1 to 3 s: wait before reading the screen.
- Apps without RPC support refuse `flipper_app_exit` even when open: press BACK instead.
- An app flagged `ext_hw` (or in a `GPIO/<board>` folder) needs a board on the GPIO header: without it, it shows an error or nothing happens.

## Transmitting and changing state

- Sub-GHz, infrared, NFC or RFID emulation and writing, Bad USB (Bad KB) scripts, U2F and Bluetooth advertising have real effects: only when the operator's request clearly asks for it.
- So do the entries that drive the GPIO pins (such as 5V on GPIO and GPIO Manual Control) and settings screens: changing a value there changes the Flipper. Look, do not change, unless asked.
- Functions that only receive (Read, Learn, a frequency analyzer) are safe to start to look; still say what you are doing.
- A catalog flag such as `subghz_tx` or `hid` says an app can do it, from a search of its code: it is a hint, never a permission.

## Power and reboot

- Holding BACK turns the Flipper on when it is off (needs checking on the Flipper).
- Holding LEFT and BACK for 5 s reboots it (needs checking on the Flipper).
- Never reboot or power off unless the operator asks: the Bluetooth link to the phone drops with it.
