# SAF + Media3 Spike

Isolated Android proof of concept for selecting a local video through `ACTION_OPEN_DOCUMENT`, persisting its read grant, inspecting the selected SAF Uri directly, and playing it with Media3. It stores the Uri string and relies on the system persisted grant; it never copies or transcodes the selected media.

## Build and install

From the repository root in PowerShell:

```powershell
.\apps\android\gradlew.bat -p apps/android :spikes:saf-media3:assembleDebug :spikes:saf-media3:testDebugUnitTest :spikes:saf-media3:lintDebug
$serial = '<connected-device-serial>'
$spikePackage = 'com.lazyeng.family.spike.safmedia3'
$activity = "$spikePackage/com.lazyeng.family.spikes.safmedia3.SafMedia3SpikeActivity"
adb -s $serial install -r apps/android/spikes/saf-media3/build/outputs/apk/debug/saf-media3-debug.apk
adb -s $serial shell am start -W -n $activity
```

`ANDROID_HOME` must be set in the calling shell, and JDK 17 configured as described by the Android project. Commands run from the repository root use `-p apps/android` so Gradle finds the Android settings.

## Generate and select the test clip

The generator requires an existing FFmpeg with libx264/AAC; it does not install tools or download media. It writes only to this module's ignored `build/fixtures` directory. The clip contains synthetic color bars and silence, with no third-party media. An explicit executable can be passed via `-Ffmpeg` if it is not on PATH.

```powershell
.\apps\android\spikes\saf-media3\New-TestClip.ps1
$clip = 'apps/android/spikes/saf-media3/build/fixtures/saf-synthetic.mp4'
$deviceClip = '/sdcard/Download/LazyEng_Spike_Test.mp4'
adb -s $serial shell test -e $deviceClip
if ($LASTEXITCODE -eq 0) { throw 'Choose an unused test filename; do not overwrite existing device media' }
adb -s $serial push $clip $deviceClip
adb -s $serial shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$deviceClip"
```

Tap **Choose local video**. In the system picker select only `LazyEng_Spike_Test.mp4` (color-bar thumbnail). It may appear under Videos/Download after the scan. The initial report must show `Persisted read grant: true`, metadata, `FIRST_FRAME_RENDERED`, and `PLAYER_STATE=READY`.

Tap Play, wait briefly, Pause, Seek to 2 seconds, and Play again. Inspect the real player callbacks:

```powershell
adb -s $serial shell run-as $spikePackage cat files/spike-report.txt
```

`IS_PLAYING`, `PAUSE_OBSERVED`, and `SEEK_OBSERVED` capture actual player state/positions. A few milliseconds of position adjustment immediately after pause is allowed for asynchronous audio-clock settling; it is not continued playback. `PLAYBACK_REQUESTED` alone is not success evidence.

## Process-death and error checks

```powershell
adb -s $serial shell pidof $spikePackage
adb -s $serial shell am force-stop $spikePackage
adb -s $serial shell pidof $spikePackage # Must return no PID.
adb -s $serial shell am start -W -n $activity
adb -s $serial shell pidof $spikePackage # New process.
adb -s $serial shell run-as $spikePackage cat files/spike-report.txt
adb -s $serial shell run-as $spikePackage ls -lR files cache shared_prefs
```

Without reselecting, require restored metadata, the persisted grant, first-frame/playing callbacks, and completion. `files` contains diagnostics/framework data only; `cache` should have no copied video. The persisted Uri is private app state, not a raw filesystem path. Do not publish the preferences contents for real user media.

For the missing-file scenario, delete only the generated sample after verifying its hash:

```powershell
$localHash = (Get-FileHash $clip -Algorithm SHA256).Hash.ToLowerInvariant()
$remoteHash = ((adb -s $serial shell sha256sum $deviceClip) -split '\s+')[0]
if ($localHash -ne $remoteHash) { throw 'Hash mismatch; do not delete' }
adb -s $serial shell rm $deviceClip
adb -s $serial shell am force-stop $spikePackage
adb -s $serial shell am start -W -n $activity
adb -s $serial shell run-as $spikePackage cat files/spike-report.txt
```

`FileNotFoundException` maps to `SOURCE_FILE_MISSING`. A `SecurityException` maps to `SOURCE_FILE_MISSING` while a persisted read grant for the Uri remains, and to `PERMISSION_REVOKED` when no such grant remains. Both direct the caller to reselect accessible media. The measured MIUI provider returned `SecurityException` after source deletion; manual revocation in Android Settings was not device-tested, and provider-specific behavior must be verified during E2-T2.

Detailed outcomes and limitations: [ADR 0006](../../../../docs/adr/0006-saf-media3-spike.md).

This is spike-only code and is not the production import workflow.
