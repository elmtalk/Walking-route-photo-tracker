# GroundTrace Android — field prototype

Native Android GPS walking-route and geotagged photograph recorder for arboricultural walkover surveys.

## Install without a laptop
1. Go to [Actions](https://github.com/elmtalk/Walking-route-photo-tracker/actions).
2. Select **Build Android APK** and open the latest successful build (or select **Run workflow**).
3. Under **Artifacts**, download **GroundTrace-debug-apk**.
4. Unzip the download on the Android phone, then open **app-debug.apk** and allow installation from that source if requested.
5. Grant *precise* location and notification permissions. Keep Location enabled.
6. In Android Settings → Apps → GroundTrace → Battery, select **Unrestricted** if available. Android menus vary.
7. Enter project name and surveyor, press **Start**, verify the persistent recording notification, then switch to Mergin Maps.

## Field operation
- Route locations are immediately appended to `track.csv` in app-private project storage.
- Foreground location service continues when the UI is closed or another app is open, subject to Android settings and GPS reception.
- 3 m minimum step or 15 second sampling; GPS fixes older than 15 s, accuracy worse than 30 m or implausible jumps are rejected.
- Camera creates full-resolution JPEG files and associates them with the latest available GPS fix (within 2 minutes). If none is available, image is saved without a marker; exported original photograph is retained.
- **Stop** recording at end. Tap **Save ZIP to Device** to select Downloads or another folder using Android's file picker, or **Share / Email Project ZIP** to send the same export via email, Drive or another app. Both options preserve the complete ZIP including KMZ, KML, CSV and original photos.
- ZIP includes `survey.kmz` (KML+photos embedded), `track.kml`, `track.csv`, `photos.csv`, `project.txt`, and original JPEG photographs. Open KMZ in Google Earth; KML/CSV in QGIS.
- GPS interruptions longer than 45 s are represented by separately styled *inferred gap* lines. **Never interpret interpolated links as inspected ground.**
- Project data remains local even when offline. Exports are portable copies; store the exported ZIP safely.
- The project must be exported before uninstalling or clearing app data.

## Limitations / QA
This is an initial field prototype, not yet confirmed to build or field tested on a physical Android device. It is not a guarantee of background GPS operation under every vendor-specific battery policy. The exported line shows route position, not a verified polygon of inspected woodland coverage. Distinguish recorded coverage from inferred lines.

The build uses a GitHub Actions debug APK (not a Play Store release) and does not need signing credentials for initial local sideloading.

For safety do a short outdoor test: record ~50 m in GroundTrace; switch to Mergin Maps; walk another ~50 m with the phone locked; return to GroundTrace, stop, export and check `survey.kmz` in Google Earth. If the track pauses, check battery restriction and precise location permissions before surveying.
