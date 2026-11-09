# bee-app

The bee app ([#156](https://github.com/tractat-us/bee/issues/156)): a Compose Multiplatform shell
whose whole screen is the board. For now it joins a board and lists its open surfaces by title,
as plain text. Rendering them is [#158](https://github.com/tractat-us/bee/issues/158), and hosting
a board on the phone is a later slice of #156.

## Run it on the desktop against your own board

Start a board, then point the app at it:

```sh
bin/bee-up --local --no-open
BEE_URL=http://127.0.0.1:8233 ./gradlew :bee-app:run
```

The app joins the board that its first argument names, else the one `$BEE_URL` names. With
neither, it asks for a link. A link is a host's root (`http://host:8233`, `ws://host:8233`) or
the join link that `bee-up` prints for a board on your network (`http://host:8233/?k=…`). On
loopback no key is needed.

Ask a Claude on the board to open a surface, and its title appears in the app.

## Android

Android is opt-in, because it needs an Android SDK and would slow every `check`:

```sh
./gradlew :bee-app:assembleDebug -Pbee.android=true
adb install bee-app/build/outputs/apk/debug/bee-app-debug.apk
```

On an emulator, the laptop's loopback is `http://10.0.2.2:8233`.

iOS waits on kuilt: `KtorRoomHost` and `KtorConnectionSource` ship for JVM and Android only
(#156, "kuilt gaps").
