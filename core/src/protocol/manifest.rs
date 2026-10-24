use serde::{Deserialize, Serialize};

/// Standard chunk size for high-speed streaming (4 MB)
pub const DEFAULT_CHUNK_SIZE: u32 = 4 * 1024 * 1024;

/// Threshold below which files are multiplexed into the continuous pack-stream (< 512 KB)
pub const SMALL_FILE_PACK_THRESHOLD: u64 = 512 * 1024;

/// Metadata for an individual file or directory in the transfer session
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileMetadata {
    /// Unique session-scoped identifier
    pub id: u64,
    /// Normalized forward-slash relative path within the transfer root (e.g. "Photos/2026/img.jpg")
    pub relative_path: String,
    /// Exact file size in bytes (0 for directories)
    pub size: u64,
    /// True if entry is a directory
    pub is_directory: bool,
    /// Nanoseconds since UNIX epoch for creation/birth time
    pub created_epoch_nanos: u64,
    /// Nanoseconds since UNIX epoch for last modified time
    pub modified_epoch_nanos: u64,
    /// Nanoseconds since UNIX epoch for last accessed time
    pub accessed_epoch_nanos: u64,
    /// Windows Read-Only attribute / Unix write permission revoked
    pub is_readonly: bool,
    /// Windows Hidden attribute / Unix dotfile
    pub is_hidden: bool,
    /// Native POSIX permission mode bits (e.g. 0o755 for dirs, 0o644 for files)
    pub unix_mode: u32,
    /// BLAKE3 256-bit cryptographic / integrity checksum
    pub blake3_hash: [u8; 32],
    /// Number of chunks for files exceeding pack threshold
    pub chunk_count: u32,
    /// Chunk size in bytes for this file
    pub chunk_size: u32,
}

impl FileMetadata {
    pub fn new_directory(
        id: u64,
        relative_path: String,
        created_epoch_nanos: u64,
        modified_epoch_nanos: u64,
        unix_mode: u32,
    ) -> Self {
        Self {
            id,
            relative_path,
            size: 0,
            is_directory: true,
            created_epoch_nanos,
            modified_epoch_nanos,
            accessed_epoch_nanos: modified_epoch_nanos,
            is_readonly: false,
            is_hidden: false,
            unix_mode: if unix_mode == 0 { 0o755 } else { unix_mode },
            blake3_hash: [0u8; 32],
            chunk_count: 0,
            chunk_size: 0,
        }
    }

    pub fn new_file(
        id: u64,
        relative_path: String,
        size: u64,
        created_epoch_nanos: u64,
        modified_epoch_nanos: u64,
        accessed_epoch_nanos: u64,
        is_readonly: bool,
        is_hidden: bool,
        unix_mode: u32,
        blake3_hash: [u8; 32],
    ) -> Self {
        let chunk_size = DEFAULT_CHUNK_SIZE;
        let chunk_count = if size == 0 {
            0
        } else {
            ((size + chunk_size as u64 - 1) / chunk_size as u64) as u32
        };

        Self {
            id,
            relative_path,
            size,
            is_directory: false,
            created_epoch_nanos,
            modified_epoch_nanos,
            accessed_epoch_nanos,
            is_readonly,
            is_hidden,
            unix_mode: if unix_mode == 0 { 0o644 } else { unix_mode },
            blake3_hash,
            chunk_count,
            chunk_size,
        }
    }

    /// Returns true if this file should be streamed via the continuous pack stream
    pub fn is_small_pack_eligible(&self) -> bool {
        !self.is_directory && self.size <= SMALL_FILE_PACK_THRESHOLD
    }
}

/// Batch Manifest sent before streaming commences
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BatchManifest {
    /// Unique 128-bit UUID for the transfer session
    pub session_id: [u8; 16],
    /// Human-readable sender device name (e.g. "Pixel 9 Pro", "Windows-Workstation")
    pub sender_device_name: String,
    /// Total count of all files (excluding directories)
    pub total_files: u64,
    /// Total count of directories
    pub total_directories: u64,
    /// Total raw bytes of all files
    pub total_bytes: u64,
    /// Ordered list of directories and files
    pub entries: Vec<FileMetadata>,
}

/// Chunk header prepended to each chunk payload
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChunkHeader {
    pub session_id: [u8; 16],
    pub file_id: u64,
    pub chunk_index: u32,
    pub offset: u64,
    pub length: u32,
    pub blake3_hash: [u8; 32],
}

/// Response from receiver after inspecting the manifest
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConflictResolution {
    Skip,
    Replace,
    RenameWithSuffix,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ManifestAck {
    pub session_id: [u8; 16],
    /// File IDs that are already identical on receiver and will be skipped
    pub skipped_file_ids: Vec<u64>,
    /// File IDs that have collisions and their resolution
    pub conflict_resolutions: Vec<(u64, ConflictResolution)>,
    /// Ready to receive
    pub accepted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChunkStatus {
    Ok,
    HashMismatch,
    DiskWriteError,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChunkAck {
    pub file_id: u64,
    pub chunk_index: u32,
    pub status: ChunkStatus,
}
