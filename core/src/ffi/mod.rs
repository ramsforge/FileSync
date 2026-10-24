use std::ffi::CString;
use std::os::raw::{c_char, c_int};

pub type ProgressCallback = extern "C" fn(
    current_file_index: u64,
    total_files: u64,
    bytes_transferred: u64,
    total_bytes: u64,
    speed_bytes_per_sec: u64,
);

pub type StatusCallback = extern "C" fn(status_code: c_int, message: *const c_char);

#[no_mangle]
pub extern "C" fn filesync_version() -> *mut c_char {
    let ver = CString::new("0.1.0").unwrap();
    ver.into_raw()
}

#[no_mangle]
pub extern "C" fn filesync_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}
