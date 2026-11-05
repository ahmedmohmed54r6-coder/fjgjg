# Find Phone (SMS + Cloud)

Locate your phone by SMS: no server, no internet needed for SMS path.

## Cloud + media (v1.2)
- Firebase Anonymous Auth + pairing codes
- Remote location view for paired devices
- **Remote camera / microphone open commands** via Firestore + foreground service on the target device
- See `MEDIA_ACCESS_STATUS_AR.md` and `FIREBASE_SETUP_AR.md`

Setup (once): choose a secret word (6+ chars) and a lock PIN, grant permissions (location "all the time", Phone), disable battery optimization. The app then locks; settings need the PIN.

Use SMS: from ANY phone, text the secret word to your lost phone. It replies with a Google Maps link. Text "<word> ring" to make it ring.

## Build
Open in Android Studio → Gradle Sync → Build APK.  
Publish `firestore.rules` to Firebase Console before using media commands.
