# FileSync 🚀

> High-speed, native, bit-for-bit LAN & USB file sharing between Android and Windows with zero data loss and full metadata preservation.

---

## Key Highlights

- **Maximum Hardware Speed**: Saturates multi-gigabit LAN, Wi-Fi 6/7, and USB 3.x (5 Gbps+) interfaces.
- **Strict Metadata Preservation**:
  - Exact nanosecond timestamps (Creation, Modification, Access).
  - Directory tree replication.
  - Bidirectional mapping between Windows attributes (Hidden, Read-Only) and POSIX permissions.
- **Bit-for-Bit Fidelity**: In-flight SIMD-accelerated BLAKE3 hashing on every chunk with instant deduplication and resume support.
- **Dual-Mode USB**:
  - **Plug & Play**: High-speed USB Tethering (RNDIS/CDC-NCM Ethernet over USB) without developer mode.
  - **Automated Fallback**: ADB Port Forwarding when USB Debugging is active.
- **Zero-Copy & Direct Disk I/O**: Contiguous disk pre-allocation (`fallocate` / `SetFileInformationByHandle`) prevents fragmentation.
- **Native Experience**:
  - **Android**: Modern Jetpack Compose UI + Background Foreground Service + Share Sheet target.
  - **Windows**: WinUI 3 Desktop App + Drag-and-drop DropZone + Explorer right-click context menu + System Tray background daemon.

---

## Monorepo Layout

```
FileSync/
├── core/                       # Shared Rust Core Transfer Engine
│   ├── src/protocol/           # Binary framing & metadata manifest
│   ├── src/fs/                 # Direct disk I/O, statx/utimensat, Win32 metadata
│   ├── src/transport/          # Socket tuning (4MB buffers, nodelay) & async streaming
│   ├── src/discovery/          # mDNS & UDP broadcast discovery
│   └── src/ffi/                # JNI & C-ABI bindings
├── android/                    # Android Native Client (Kotlin / Jetpack Compose)
│   ├── app/src/main/java/      # Jetpack Compose UI, Foreground Service, Share Sheet target
│   └── app/src/main/jniLibs/   # Staged libfilesync_core.so
├── windows/                    # Windows Native Client (WinUI 3 / .NET 9)
│   ├── FileSync.App/           # Modern Fluent desktop UI with drag-and-drop
│   └── FileSync.sln            # Visual Studio / .NET Solution
└── scripts/                    # Build orchestration & dev setup scripts
    ├── build_core.ps1          # Builds Rust core and stages DLL & .so files
    └── setup_dev.ps1           # Verifies developer environment
```

---

## Quick Start & Verification

### 1. Build Rust Core & Stage Binaries
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build_core.ps1 -Configuration Release
```

### 2. Run Rust Core Test Suite
```powershell
cd core
cargo test
```

### 3. Build Windows App
```powershell
dotnet build windows\FileSync.sln -c Release
```

### 4. Build Android App
Open `android/` in Android Studio or build via Gradle:
```powershell
cd android
.\gradlew assembleDebug
```
