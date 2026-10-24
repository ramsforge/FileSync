# FileSync 🚀

> High-speed, native, bit-for-bit LAN & USB file sharing between Android and Windows with zero data loss and full metadata preservation.

---

## Key Highlights

- **Maximum Hardware Speed**: Saturates multi-gigabit LAN, Wi-Fi 6/7, and USB 3.x (5 Gbps+) interfaces.
- **Strict Metadata Preservation**:
  - Nanosecond timestamps (Creation, Modification, Access).
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
├── windows/                    # Windows Native Client (WinUI 3 / .NET 9)
└── scripts/                    # Build orchestration & dev setup scripts
```

---

## Quick Start

### 1. Build Rust Core & Stage Binaries
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build_core.ps1 -Configuration Release
```

### 2. Run Test Suite
```powershell
cd core
cargo test
```
