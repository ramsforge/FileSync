use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::Path;

/// Buffer size for streaming hashing and disk I/O (256 KB)
pub const IO_BUFFER_SIZE: usize = 256 * 1024;

/// Pre-allocate disk space contiguously to prevent disk fragmentation
/// and immediately verify sufficient storage space.
pub fn allocate_disk_space(file: &File, size: u64) -> io::Result<()> {
    if size == 0 {
        return Ok(());
    }

    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        use windows_sys::Win32::Storage::FileSystem::{
            SetFileInformationByHandle, FileAllocationInfo, FileEndOfFileInfo,
            FILE_ALLOCATION_INFO, FILE_END_OF_FILE_INFO,
        };

        let handle = file.as_raw_handle() as windows_sys::Win32::Foundation::HANDLE;

        // 1. Set File Allocation Info (reserves physical disk clusters)
        let mut alloc_info = FILE_ALLOCATION_INFO {
            AllocationSize: size as i64,
        };
        let res_alloc = unsafe {
            SetFileInformationByHandle(
                handle,
                FileAllocationInfo,
                &mut alloc_info as *mut _ as *mut _,
                std::mem::size_of::<FILE_ALLOCATION_INFO>() as u32,
            )
        };
        if res_alloc == 0 {
            return Err(io::Error::last_os_error());
        }

        // 2. Set End Of File Info (sets logical length)
        let mut eof_info = FILE_END_OF_FILE_INFO {
            EndOfFile: size as i64,
        };
        let res_eof = unsafe {
            SetFileInformationByHandle(
                handle,
                FileEndOfFileInfo,
                &mut eof_info as *mut _ as *mut _,
                std::mem::size_of::<FILE_END_OF_FILE_INFO>() as u32,
            )
        };
        if res_eof == 0 {
            return Err(io::Error::last_os_error());
        }
    }

    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        let fd = file.as_raw_fd();
        let ret = unsafe { libc::posix_fallocate(fd, 0, size as libc::off_t) };
        if ret != 0 {
            return Err(io::Error::from_raw_os_error(ret));
        }
    }

    #[cfg(not(any(windows, unix)))]
    {
        file.set_len(size)?;
    }

    Ok(())
}

/// Computes the SIMD-accelerated BLAKE3 hash of an entire file
pub fn compute_file_blake3(path: &Path) -> io::Result<[u8; 32]> {
    let mut file = File::open(path)?;
    let mut hasher = blake3::Hasher::new();
    let mut buffer = vec![0u8; IO_BUFFER_SIZE];

    loop {
        let bytes_read = file.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }

    Ok(*hasher.finalize().as_bytes())
}

/// Computes the BLAKE3 hash of an in-memory byte slice
pub fn compute_chunk_blake3(chunk_data: &[u8]) -> [u8; 32] {
    *blake3::hash(chunk_data).as_bytes()
}

/// Positional write into a file at specific offset
pub fn write_chunk_at(file: &mut File, offset: u64, data: &[u8]) -> io::Result<()> {
    file.seek(SeekFrom::Start(offset))?;
    file.write_all(data)?;
    Ok(())
}

/// Positional read from a file at specific offset
pub fn read_chunk_at(file: &mut File, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
    file.seek(SeekFrom::Start(offset))?;
    file.read(buf)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    #[test]
    fn test_disk_allocation_and_chunk_io() {
        let temp_file = NamedTempFile::new().unwrap();
        let file = temp_file.reopen().unwrap();

        let target_size = 10 * 1024 * 1024; // 10 MB
        allocate_disk_space(&file, target_size).expect("Disk allocation should succeed");

        assert_eq!(file.metadata().unwrap().len(), target_size);

        let mut file_mut = file;
        let test_payload = b"High-speed data chunk for testing SIMD BLAKE3";
        let offset = 4 * 1024 * 1024; // 4 MB offset

        write_chunk_at(&mut file_mut, offset, test_payload).unwrap();

        let mut read_buf = vec![0u8; test_payload.len()];
        let read_bytes = read_chunk_at(&mut file_mut, offset, &mut read_buf).unwrap();

        assert_eq!(read_bytes, test_payload.len());
        assert_eq!(&read_buf[..], test_payload);

        let chunk_hash = compute_chunk_blake3(test_payload);
        assert_eq!(chunk_hash, *blake3::hash(test_payload).as_bytes());
    }
}
