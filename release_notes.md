# FileSync v0.1.0 — High-Speed Local File Transfer for Windows & Android

FileSync provides ultra-fast, bit-for-bit file synchronization between Windows PC and Android devices over USB (ADB) or Wi-Fi LAN with zero third-party cloud dependence.

---

### What's New in v0.1.0

#### 📂 Custom Output Destination Folders
- **Windows**: Choose custom destination directories with the native Windows folder picker (`OpenFolderDialog`). Persisted to local settings and loaded automatically on startup.
- **Android**: Configure custom received file destinations in the **Received Files** tab. Choose from 1-tap presets (*Downloads/FileSync*, *Downloads Root*, *DCIM/Photos*, *Documents*) or browse custom folders via Android Storage Access Framework (SAF).
- **Dynamic Receiver**: Both Windows and Android transfer receivers dynamically route incoming files directly to the selected destination folder.

#### 🛠️ Windows Desktop Improvements
- **"Received Files" Launcher Fix**: Fixed Windows 11 ShellExecute directory launch issues by using explicit `explorer.exe` process invocation.
- **Output Destination Bar**: Added current path display, "Change Folder...", and "Open Folder" buttons directly above the drag-and-drop zone.
- **Automatic USB ADB Setup**: Auto-detects connected Android devices over USB cable and configures ADB port forwarding (`45782` ➔ `45780`) and reverse tunnels (`45783` ➔ `45781`) automatically.
- **Non-Blocking Notifications**: Replaced modal popups with inline status reporting for an uninterrupted workflow.

#### ⚡ Protocol & UI/UX Engine
- **Deadlock-Free Streaming**: Symmetrical handshake with explicit `BatchComplete` frames and socket timeouts.
- **Atomic Staging**: Files are staged as `.part` files and committed only upon verified SHA-256 integrity and nanosecond timestamp preservation.
- **Continuous Multi-Batch Queue**: Real-time per-file progress animations (Pending ➔ InProgress ➔ Sent), "Clear Sent" queue cleanup, and dynamic "All Sent (+ Add More)" action button.
- **System File Manager Integration**: Open received files directly in system viewers (Gallery, PDF Reader) or open the Android Files app with a single tap.

---

### Downloads & Assets

- **Android App**: `FileSync-v0.1.0-android.apk` (Supports ARMv7, ARM64, and x86_64; compatible with Android 8.0+).
- **Windows App**: `FileSync-v0.1.0-windows-x64.zip` (Extract and run `FileSync.App.exe` on Windows 10/11 x64).
