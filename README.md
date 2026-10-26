# _slate

Custom home screen for Fire TV. Your apps, wallpapers, and settings without all the clutter. Still a work in progress. Made for Android-based Fire OS devices; Vega OS isn’t supported.

## Installation

1. Download `Slate-firetv.apk` from [Releases](https://github.com/dgonzgomez/_slate/releases).
2. On the TV, turn on **Developer options → ADB debugging**. If Developer options is hidden, go to **About** and press Select on the device name seven times.
3. Find the TV’s IP address in its network settings.
4. With [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) on your computer, install Slate:

   ```sh
   adb connect YOUR_TV_ADDRESS:5555
   adb -s YOUR_TV_ADDRESS:5555 install -r Slate-firetv.apk
   ```

   Accept the debugging prompt on the TV when it appears.

5. To have the Home button open Slate, run:

   ```sh
   adb -s YOUR_TV_ADDRESS:5555 shell pm grant dev.slate android.permission.READ_LOGS
   adb -s YOUR_TV_ADDRESS:5555 shell pm grant dev.slate android.permission.DUMP
   adb -s YOUR_TV_ADDRESS:5555 shell appops set dev.slate GET_USAGE_STATS allow
   adb -s YOUR_TV_ADDRESS:5555 shell am start -n dev.slate/.MainActivity
   ```

Home button support depends on your Fire OS version. If your TV blocks the permissions, you can still open Slate from Apps. Amazon Home stays installed and enabled.
