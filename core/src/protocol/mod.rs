pub mod frame;
pub mod manifest;

pub use frame::{Frame, FrameError, MessageType, HEADER_SIZE, MAX_FRAME_PAYLOAD};
pub use manifest::{
    BatchManifest, ChunkAck, ChunkHeader, ChunkStatus, ConflictResolution, FileMetadata,
    ManifestAck, DEFAULT_CHUNK_SIZE, SMALL_FILE_PACK_THRESHOLD,
};

/// Helper to serialize any serializable payload into a typed Frame
pub fn encode_message<T: serde::Serialize>(
    msg_type: MessageType,
    payload: &T,
) -> Result<Frame, FrameError> {
    let bytes = bincode::serialize(payload)
        .map_err(|e| FrameError::Serialization(e.to_string()))?;
    Ok(Frame::new(msg_type, bytes))
}

/// Helper to deserialize a typed payload from a Frame
pub fn decode_message<T: serde::de::DeserializeOwned>(frame: &Frame) -> Result<T, FrameError> {
    bincode::deserialize(&frame.payload)
        .map_err(|e| FrameError::Serialization(e.to_string()))
}
