# ShipApp

Android Studio project for shipment lookup and alert monitoring.

## Current Direction

This build supports three credential paths:

- Mock mode, no key stored yet
- Debug seed mode, files bundled only for local testing
- User import mode, where the user picks Cafe24 JSON files and types Coupang keys inside the app

## Debug Seed Files

These files are intentionally ignored by git. Add them only on your local machine.

- `app/src/debug/assets/seeds/cafe24_home.json`
- `app/src/debug/assets/seeds/cafe24_prepare.json`
- `app/src/debug/assets/seeds/coupang.properties`

Example `coupang.properties`:

```properties
vendorId=A00000000
accessKey=your-access-key
secretKey=your-secret-key
```

## Important

The app currently stores imported keys in local app preferences for MVP testing. That is acceptable for early device testing, but it is not the final security model. The next hardening step is encrypted storage, followed by replacing direct mobile API calls with a backend if needed.

## Open In Android Studio

1. Open the `shipapp` folder in Android Studio.
2. Let Gradle sync.
3. `local.properties` already points at the local SDK on this machine.
4. Run the `app` configuration.

## Current Structure

- `app/src/main/java/com/rkghrud/shipapp/MainActivity.java`: dashboard, key import, and alert toggles
- `app/src/main/java/com/rkghrud/shipapp/data/CredentialStore.java`: local credential storage
- `app/src/main/java/com/rkghrud/shipapp/data/DebugSeedLoader.java`: debug asset import helper
- `app/src/main/java/com/rkghrud/shipapp/notifications`: notification helper
- `app/src/main/java/com/rkghrud/shipapp/workers`: periodic alert worker
