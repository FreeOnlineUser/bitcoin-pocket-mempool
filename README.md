# Bitcoin Pocket Mempool

A sovereign Bitcoin mempool explorer for Android. Connect to any Bitcoin node with RPC access to visualize and explore its mempool in real-time.

## Features

- **Connect to any Bitcoin node** - Local or remote nodes with RPC access
- **Real-time mempool visualization** - See projected blocks and fee distribution
- **Transaction search** - Look up specific transactions in the mempool
- **Fee rate analysis** - Understand current fee market conditions
- **Home screen widget** - Keep an eye on mempool stats at a glance
- **Notifications** - Get alerts for transaction confirmations and blocks
- **Offline-first** - No external APIs or third-party services required

## Screenshots

*Coming soon*

## Setup

### Prerequisites

- Android 8.0+ (API level 26+)
- A Bitcoin node with RPC access enabled (local or remote)

### Connecting to your Bitcoin node

1. Ensure your Bitcoin node has RPC access enabled
2. Open the app and configure your connection settings:
   - **Host**: Your node's IP address (e.g., `192.168.1.100` or `localhost`)
   - **Port**: RPC port (default: `8332`)
   - **Username**: RPC username from your `bitcoin.conf`
   - **Password**: RPC password from your `bitcoin.conf`
   - **Use SSL**: Enable if your node supports HTTPS

### Local Bitcoin Core setup

If running Bitcoin Core locally, add these lines to your `bitcoin.conf`:

```conf
server=1
rpcuser=yourusername
rpcpassword=yourpassword
rpcallowip=127.0.0.1
rpcallowip=192.168.0.0/16
rpcport=8332
```

## Building from Source

### Requirements

- JDK 17+
- Android SDK (API 34)
- Rust toolchain with Android targets
- NDK for cross-compilation

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/FreeOnlineUser/bitcoin-pocket-mempool.git
   cd bitcoin-pocket-mempool
   ```

2. Set up environment variables:
   ```bash
   export JAVA_HOME=~/tools/jdk-17.0.13+11/Contents/Home
   export ANDROID_HOME=~/tools/android-sdk
   ```

3. Install Rust Android target:
   ```bash
   rustup target add aarch64-linux-android
   ```

4. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

5. Install on device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Technology Stack

- **Kotlin** - Modern Android development
- **Jetpack Compose** - Declarative UI framework
- **Rust** - High-performance mempool analysis (GBT algorithm)
- **JNI** - Native library integration
- **RPC** - Direct Bitcoin node communication

## Architecture

The app connects directly to Bitcoin nodes via RPC calls, eliminating dependence on external APIs. The core mempool analysis runs on a Rust library (derived from mempool.space) for optimal performance.

## Privacy & Security

- **No external services** - All data comes directly from your Bitcoin node
- **Local processing** - Mempool analysis happens on-device
- **No tracking** - We don't collect any user data
- **Open source** - Fully auditable code

## License

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).

Portions of this code are derived from mempool.space and subject to AGPL-3.0.
https://github.com/mempool/mempool/tree/master/rust/gbt

## Contributing

Contributions are welcome! Please feel free to submit pull requests, report bugs, or suggest features.

## Support

For support or questions, please open an issue on GitHub.

---

**Disclaimer**: This software is provided as-is. Always verify transaction details through your own Bitcoin node before making financial decisions.