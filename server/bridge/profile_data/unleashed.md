# Profile: Unleashed

Repository data for the agent: what is Unleashed's own, after the base profile. It is written from Unleashed's public
sources only, not tried on a Flipper. A line ending in "(needs checking on the Flipper)" has not been tried on a real
Flipper yet: trust the screen over it.

## The main menu

- Sub-GHz, 125 kHz RFID, NFC, Infrared, GPIO, iButton, Bad USB, U2F, Clock, Apps, Settings by default (needs checking on the Flipper).
- Clock is in the main menu (needs checking on the Flipper).
- There is no firmware settings app in the main menu: its options are in the usual Settings (needs checking on the Flipper).

## The desktop

- UP opens the lock menu, as on the official firmware (needs checking on the Flipper).

## Apps and their paths

- The apps come prebuilt in two packs: a base pack and an extra pack, chosen when the firmware is installed; a Flipper may have either or both (needs checking on the Flipper).
- Categories are flat: `/ext/apps/<Category>/<appid>.fap`, with no subfolders (needs checking on the Flipper).
- More Sub-GHz apps come with the firmware itself (Nice O-Code, Security+ PIN, Seed Capturer), and MFKey in NFC (needs checking on the Flipper).
- The app catalog follows the apps bundle the release names: a Flipper updated with another bundle may have other apps, so match them by `flipper_firmware`'s list.

## Settings

- Settings also has an Input entry (needs checking on the Flipper).
