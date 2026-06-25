# Minima Faucet (native Android)

A native Android port of the Minima **Faucet** MiniDapp. It fetches a wallet address from the local
**Minima Core** node over the node's **broadcast‑Intent IPC** (`minimaapi`) and requests test funds from
the faucet backend.

This was the first native Minima companion app — built to master the IPC before the larger ports
(utxo wallet, vestr).

## Build
Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Install, then enable **Minima Faucet** in Minima Core → Apps to authorize the IPC.

## Releases
Versioned APKs + changelog: **[eurobuddha/minima-core-apks](https://github.com/eurobuddha/minima-core-apks)**
(tags `minima-faucet-v<version>`).
