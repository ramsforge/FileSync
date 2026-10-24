use std::fs;
use std::io;
use std::path::Path;
use std::time::UNIX_EPOCH;
use crate::protocol::manifest::FileMetadata;

#[cfg(windows)]
use std::os::windows::fs::MetadataExt;

/// Convert nanoseconds since Unix epoch to Windows FILETIME (100-nanosecond intervals since Jan 1, 1601 UTC)
#[cfg(windows)]
pub fn nanos_to_filetime(nanos: u64) -> u64 {
    const WINDOWS_EPOCH_DIFF_NANOS: u64 = 11_644_473_600 * 1_000_000_000;
    (nanos.saturating_add(WINDOWS_EPOCH_DIFF_NANOS)) / 100
}

/// Convert Windows FILETIME to Unix epoch nanoseconds
#[cfg(windows)]
pub fn filetime_to_nanos(filetime: u64) -> u64 {
    const WINDOWS_EPOCH_DIFF_NANOS: u64 = 11_644_473_600 * 1_000_000_000;
    let nanos_total = filetime.saturating_mul(100);
    nanos_total.saturating_sub(WINDOWS_EPOCH_DIFF_NANOS)
}

/// Read comprehensive metadata from local filesystem
pub fn read_metadata(path: &Path, relative_path: String) -> io::Result<FileMetadata> {
    let std_meta = fs::symlink_metadata(path)?;
    let is_dir = std_meta.is_dir();
    let size = if is_dir { 0 } else { std_meta.len() };

    let modified_nanos = std_meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);

    let accessed_nanos = std_meta
        .accessed()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(modified_nanos);

    let created_nanos = std_meta
        .created()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(modified_nanos);

    #[cfg(windows)]
    let (is_readonly, is_hidden, unix_mode) = {
        let attrs = std_meta.file_attributes();
        use windows_sys::Win32::Storage::FileSystem::{FILE_ATTRIBUTE_HIDDEN, FILE_ATTRIBUTE_READONLY};
        let readonly = (attrs & FILE_ATTRIBUTE_READONLY) != 0;
        let hidden = (attrs & FILE_ATTRIBUTE_HIDDEN) != 0;
        let mode = if is_dir {
            if readonly { 0o555 } else { 0o755 }
        } else {
            if readonly { 0o444 } else { 0o644 }
        };
        (readonly, hidden, mode)
    };

    #[cfg(unix)]
    let (is_readonly, is_hidden, unix_mode) = {
        use std::os::unix::fs::MetadataExt;
        let mode = std_meta.mode();
        let readonly = (mode & 0o222) == 0;
        let filename = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
        let hidden = filename.starts_with('.');
        (readonly, hidden, mode & 0o777)
    };

    #[cfg(not(any(windows, unix)))]
    let (is_readonly, is_hidden, unix_mode) = (
        std_meta.permissions().readonly(),
        false,
        if is_dir { 0o755 } else { 0o644 },
    );

    let blake3_hash = [0u8; 32];

    if is_dir {
        Ok(FileMetadata::new_directory(
            0,
            relative_path,
            created_nanos,
            modified_nanos,
            unix_mode,
        ))
    } else {
        Ok(FileMetadata::new_file(
            0,
            relative_path,
            size,
            created_nanos,
            modified_nanos,
            accessed_nanos,
            is_readonly,
            is_hidden,
            unix_mode,
            blake3_hash,
        ))
    }
}

/// Restore metadata (exact creation, modification, access timestamps, and attributes) onto destination path
pub fn restore_metadata(path: &Path, meta: &FileMetadata) -> io::Result<()> {
    #[cfg(windows)]
    {
        use std::fs::OpenOptions;
        use std::os::windows::fs::OpenOptionsExt;
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::Storage::FileSystem::{
            FILE_BASIC_INFO, SetFileInformationByHandle, FileBasicInfo,
            FILE_ATTRIBUTE_DIRECTORY, FILE_ATTRIBUTE_HIDDEN, FILE_ATTRIBUTE_NORMAL,
            FILE_ATTRIBUTE_READONLY, FILE_FLAG_BACKUP_SEMANTICS, FILE_WRITE_ATTRIBUTES,
        };

        let file = OpenOptions::new()
            .access_mode(FILE_WRITE_ATTRIBUTES)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
            .open(path)?;

        let handle = file.as_raw_handle() as windows_sys::Win32::Foundation::HANDLE;

        let mut basic_info = FILE_BASIC_INFO {
            CreationTime: nanos_to_filetime(meta.created_epoch_nanos) as i64,
            LastAccessTime: nanos_to_filetime(meta.accessed_epoch_nanos) as i64,
            LastWriteTime: nanos_to_filetime(meta.modified_epoch_nanos) as i64,
            ChangeTime: 0,
            FileAttributes: {
                let mut attr = if meta.is_directory {
                    FILE_ATTRIBUTE_DIRECTORY
                } else {
                    FILE_ATTRIBUTE_NORMAL
                };
                if meta.is_readonly {
                    attr |= FILE_ATTRIBUTE_READONLY;
                }
                if meta.is_hidden {
                    attr |= FILE_ATTRIBUTE_HIDDEN;
                }
                attr
            },
        };

        let res = unsafe {
            SetFileInformationByHandle(
                handle,
                FileBasicInfo,
                &mut basic_info as *mut _ as *mut _,
                std::mem::size_of::<FILE_BASIC_INFO>() as u32,
            )
        };

        if res == 0 {
            return Err(io::Error::last_os_error());
        }
    }

    #[cfg(unix)]
    {
        use std::ffi::CString;
        use std::os::unix::ffi::OsStrExt;

        let c_path = CString::new(path.as_os_str().as_bytes())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;

        let times = [
            libc::timespec {
                tv_sec: (meta.accessed_epoch_nanos / 1_000_000_000) as libc::time_t,
                tv_nsec: (meta.accessed_epoch_nanos % 1_000_000_000) as libc::c_long,
            },
            libc::timespec {
                tv_sec: (meta.modified_epoch_nanos / 1_000_000_000) as libc::time_t,
                tv_nsec: (meta.modified_epoch_nanos % 1_000_000_000) as libc::c_long,
            },
        ];

        let ret = unsafe { libc::utimensat(libc::AT_FDCWD, c_path.as_ptr(), times.as_ptr(), 0) };
        if ret != 0 {
            return Err(io::Error::last_os_error());
        }

        if meta.unix_mode != 0 {
            let ret = unsafe { libc::chmod(c_path.as_ptr(), meta.unix_mode as libc::mode_t) };
            if ret != 0 {
                return Err(io::Error::last_os_error());
            }
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    #[test]
    fn test_metadata_read_and_restore() {
        let temp_file = NamedTempFile::new().unwrap();
        let path = temp_file.path();

        let initial_meta = read_metadata(path, "test.txt".to_string()).unwrap();
        assert_eq!(initial_meta.size, 0);

        let target_epoch_nanos = 1_767_225_600 * 1_000_000_000;
        let mut modified_meta = initial_meta.clone();
        modified_meta.modified_epoch_nanos = target_epoch_nanos;
        modified_meta.created_epoch_nanos = target_epoch_nanos;
        modified_meta.accessed_epoch_nanos = target_epoch_nanos;

        restore_metadata(path, &modified_meta).expect("Restore metadata should succeed");

        let re_read = read_metadata(path, "test.txt".to_string()).unwrap();

        let diff = (re_read.modified_epoch_nanos as i128 - target_epoch_nanos as i128).abs();
        assert!(
            diff < 2_000_000,
            "Timestamp difference too large: {} ns",
            diff
        );
    }
}
