# Profile: the official firmware

Repository data for the agent, written from the official firmware's public documentation and sources: what is its
own, after the base profile. A line ending in "(needs checking on the Flipper)" has not been tried on a real Flipper
yet: trust the screen over it.

## The main menu

- Sub-GHz, 125 kHz RFID, NFC, Infrared, GPIO, iButton, Bad USB, U2F, Apps, Settings, in that order unless changed (needs checking on the Flipper).
- Apps holds the installed apps, one folder per category: Bluetooth, GPIO, Games, Infrared, iButton, Media, NFC, RFID, Sub-GHz, Tools, USB (needs checking on the Flipper).
- Built-in apps start by their menu name, for example "Sub-GHz", "NFC" or "Infrared" (needs checking on the Flipper).

## The desktop

- UP opens the lock menu: Lock, Mute and Dummy Mode (needs checking on the Flipper).
- Holding DOWN opens Device Info (needs checking on the Flipper).
- In Dummy Mode most functions are off; to leave it, press UP on the desktop and choose Default Mode (needs checking on the Flipper).

## Settings

- Bluetooth, LCD and Notifications, Storage, Power, Desktop, Passport, System, Expansion Modules, Clock & Alarm and About (needs checking on the Flipper).
- Settings, System, Hand Orient switches to left-handed mode, which swaps the directions on screen (needs checking on the Flipper).
- Settings, Desktop sets the quick access apps and the PIN for the lock (needs checking on the Flipper).

## Apps

- Few apps come with the firmware (Snake Game, the Bluetooth and USB remotes); the rest are installed from the official app catalog, whose entries `flipper_firmware` names (needs checking on the Flipper).
- The catalog's entries for apps from the official app catalog have no capability flags (`[not searched]`): their code is not in the firmware's repository.

## Power

- Holding BACK for 30 s forces a hard reboot (needs checking on the Flipper).
