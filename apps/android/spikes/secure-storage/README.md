# Secure Storage Spike

This isolated application validates Android Keystore behavior and the E1-T2 PIN KDF/lockout parameters. It is pre-production spike code, not a production vault or PIN implementation. It creates only random synthetic bytes at runtime; no fixed API key or PIN is embedded, displayed, or logged.

The app writes only AES-GCM ciphertext, a SHA-256 comparison digest, verifier salt/hash/iteration parameters, and lockout metadata to its private files/preferences. Test output contains status and timing only. The debug package is independent of the family app.

## Run on a device

With `ANDROID_HOME` and the project JDK 17 configured:

```powershell
.\apps\android\gradlew.bat :spikes:secure-storage:assembleDebug :spikes:secure-storage:lintDebug
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$serial = "<connected-device-serial>"
$pkg = "com.lazyeng.family.spike.securestorage"
$activity = "com.lazyeng.family.spike.securestorage/com.lazyeng.family.spikes.securestorage.SecureStorageSpikeActivity"
$apk = "apps/android/spikes/secure-storage/build/outputs/apk/debug/secure-storage-debug.apk"
& $adb -s $serial install -r $apk
& $adb -s $serial shell pm clear $pkg
& $adb -s $serial shell am start -S -W -f 0x10008000 -n $activity --es mode baseline
& $adb -s $serial shell run-as $pkg cat files/spike-report.txt
```

After baseline completes, verify a real app-process restart:

```powershell
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -S -W -n $activity --es mode verify-process
& $adb -s $serial shell run-as $pkg cat files/spike-report.txt
```

To verify that uninstall removes the old Keystore alias while a copy of its ciphertext survives outside app data:

```powershell
$cipher = (& $adb -s $serial shell run-as $pkg cat files/probe.b64).Trim()
$digest = (& $adb -s $serial shell run-as $pkg cat files/probe.sha256).Trim()
& $adb -s $serial uninstall $pkg
& $adb -s $serial install $apk
& $adb -s $serial shell am start -S -W -n $activity --es mode verify-uninstall --es cipher $cipher --es digest $digest
& $adb -s $serial shell run-as $pkg cat files/spike-report.txt
```

The host variables carry only encrypted test bytes and a one-way digest. Never replace the generated test material with real credentials.
