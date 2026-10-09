BUILD APK
1. Install Android Studio (Koala or newer).
2. File > Open > select this ElmDiag folder. Wait for Gradle sync.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. APK: app/build/outputs/apk/debug/app-debug.apk
Or in terminal: ./gradlew assembleDebug  (Android Studio creates the wrapper)

USE
1. Plug ELM327 into OBD port, ignition ON.
2. Pair ELM327 in Android Bluetooth settings (PIN 1234 or 0000).
3. Open app, pick adapter, tap Connect.
