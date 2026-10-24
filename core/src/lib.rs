pub mod discovery;
pub mod ffi;
pub mod fs;
pub mod protocol;
pub mod transport;

pub use discovery::{PeerBeacon, DISCOVERY_PORT, DEFAULT_SERVICE_PORT};
pub use fs::{allocate_disk_space, compute_chunk_blake3, compute_file_blake3, read_metadata, restore_metadata};
pub use protocol::{
    BatchManifest, ChunkAck, ChunkHeader, FileMetadata, Frame, FrameError, MessageType,
};
pub use transport::{tune_socket, FrameReader, FrameWriter, SOCKET_BUFFER_SIZE};
