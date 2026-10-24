use std::io;
use bytes::{Buf, BufMut, BytesMut};
use crc32fast::Hasher;
use thiserror::Error;

pub const MAGIC: [u8; 5] = *b"FSYNC";
pub const PROTOCOL_VERSION: u8 = 1;
pub const HEADER_SIZE: usize = 12;
pub const MAX_FRAME_PAYLOAD: usize = 16 * 1024 * 1024; // 16 MB

#[derive(Error, Debug)]
pub enum FrameError {
    #[error("Incomplete frame header: expected {0} bytes, got {1}")]
    IncompleteHeader(usize, usize),
    #[error("Incomplete frame payload: expected {0} bytes, got {1}")]
    IncompletePayload(usize, usize),
    #[error("Invalid magic bytes: {0:?}")]
    InvalidMagic([u8; 5]),
    #[error("Unsupported protocol version: {0}")]
    UnsupportedVersion(u8),
    #[error("Header CRC mismatch: expected {expected:#x}, calculated {calculated:#x}")]
    HeaderCrcMismatch { expected: u32, calculated: u32 },
    #[error("Payload CRC mismatch: expected {expected:#x}, calculated {calculated:#x}")]
    PayloadCrcMismatch { expected: u32, calculated: u32 },
    #[error("Payload exceeds maximum size: {0} bytes (max: {1})")]
    PayloadTooLarge(usize, usize),
    #[error("IO error: {0}")]
    Io(#[from] io::Error),
    #[error("Serialization error: {0}")]
    Serialization(String),
}

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageType {
    HandshakeHello = 0x01,
    HandshakeAck = 0x02,
    BatchManifest = 0x10,
    ManifestAck = 0x11,
    ChunkData = 0x20,
    ChunkAck = 0x21,
    FileComplete = 0x22,
    BatchComplete = 0x23,
    PackStreamStart = 0x30,
    PackStreamChunk = 0x31,
    PackStreamEnd = 0x32,
    Ping = 0x40,
    Pong = 0x41,
    ErrorMessage = 0xFF,
}

impl TryFrom<u8> for MessageType {
    type Error = FrameError;

    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x01 => Ok(Self::HandshakeHello),
            0x02 => Ok(Self::HandshakeAck),
            0x10 => Ok(Self::BatchManifest),
            0x11 => Ok(Self::ManifestAck),
            0x20 => Ok(Self::ChunkData),
            0x21 => Ok(Self::ChunkAck),
            0x22 => Ok(Self::FileComplete),
            0x23 => Ok(Self::BatchComplete),
            0x30 => Ok(Self::PackStreamStart),
            0x31 => Ok(Self::PackStreamChunk),
            0x32 => Ok(Self::PackStreamEnd),
            0x40 => Ok(Self::Ping),
            0x41 => Ok(Self::Pong),
            0xFF => Ok(Self::ErrorMessage),
            unknown => Err(FrameError::Serialization(format!("Unknown message type: 0x{:02X}", unknown))),
        }
    }
}

/// A decoded network frame
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub msg_type: MessageType,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(msg_type: MessageType, payload: Vec<u8>) -> Self {
        Self { msg_type, payload }
    }

    /// Serializes the frame into bytes with CRC protection
    pub fn encode(&self) -> Vec<u8> {
        let payload_len = self.payload.len() as u32;

        // Header:
        // [0..5]   Magic: FSYNC
        // [5]      Version: 1
        // [6]      MsgType: u8
        // [7]      Reserved: 0
        // [8..12]  PayloadLen: u32 (little endian)
        let mut header_bytes = [0u8; 8];
        header_bytes[0..5].copy_from_slice(&MAGIC);
        header_bytes[5] = PROTOCOL_VERSION;
        header_bytes[6] = self.msg_type as u8;
        header_bytes[7] = 0; // Reserved for flags/future

        // Calculate Header CRC (12 bytes: 8 bytes header_bytes + 4 bytes payload_len)
        let mut full_h_hasher = Hasher::new();
        full_h_hasher.update(&header_bytes);
        full_h_hasher.update(&payload_len.to_le_bytes());
        let full_header_crc = full_h_hasher.finalize();

        // Let's lay out the 16-byte fixed header:
        // [0..5]   Magic "FSYNC" (5)
        // [5]      Version (1)
        // [6]      MsgType (1)
        // [7]      Reserved (1)
        // [8..12]  PayloadLen (4)
        // [12..16] HeaderCRC (4)
        let mut final_out = Vec::with_capacity(16 + self.payload.len() + 4);
        final_out.extend_from_slice(&header_bytes);
        final_out.put_u32_le(payload_len);
        final_out.put_u32_le(full_header_crc);

        // Payload
        final_out.extend_from_slice(&self.payload);

        // Payload CRC
        let mut p_hasher = Hasher::new();
        p_hasher.update(&self.payload);
        final_out.put_u32_le(p_hasher.finalize());

        final_out
    }

    /// Attempts to decode a frame from an incoming buffer.
    /// Returns Ok(Some(Frame)) if a complete frame was parsed,
    /// Ok(None) if more data is needed, or Err(FrameError) on corruption.
    pub fn decode(src: &mut BytesMut) -> Result<Option<Self>, FrameError> {
        const FIXED_HEADER_LEN: usize = 16;
        if src.len() < FIXED_HEADER_LEN {
            return Ok(None);
        }

        // Validate Magic
        let mut magic = [0u8; 5];
        magic.copy_from_slice(&src[0..5]);
        if magic != MAGIC {
            return Err(FrameError::InvalidMagic(magic));
        }

        // Validate Version
        let version = src[5];
        if version != PROTOCOL_VERSION {
            return Err(FrameError::UnsupportedVersion(version));
        }

        let msg_type_raw = src[6];
        let msg_type = MessageType::try_from(msg_type_raw)?;

        // Payload Length
        let payload_len = u32::from_le_bytes([src[8], src[9], src[10], src[11]]) as usize;
        if payload_len > MAX_FRAME_PAYLOAD {
            return Err(FrameError::PayloadTooLarge(payload_len, MAX_FRAME_PAYLOAD));
        }

        // Verify Header CRC
        let expected_header_crc = u32::from_le_bytes([src[12], src[13], src[14], src[15]]);
        let mut h_hasher = Hasher::new();
        h_hasher.update(&src[0..12]);
        let calculated_header_crc = h_hasher.finalize();
        if expected_header_crc != calculated_header_crc {
            return Err(FrameError::HeaderCrcMismatch {
                expected: expected_header_crc,
                calculated: calculated_header_crc,
            });
        }

        // Check if full payload + 4-byte payload CRC is available
        let total_frame_len = FIXED_HEADER_LEN + payload_len + 4;
        if src.len() < total_frame_len {
            return Ok(None);
        }

        // Advance past header
        src.advance(FIXED_HEADER_LEN);

        // Extract payload
        let payload = src.split_to(payload_len).to_vec();

        // Extract and verify Payload CRC
        let expected_payload_crc = src.get_u32_le();
        let mut p_hasher = Hasher::new();
        p_hasher.update(&payload);
        let calculated_payload_crc = p_hasher.finalize();
        if expected_payload_crc != calculated_payload_crc {
            return Err(FrameError::PayloadCrcMismatch {
                expected: expected_payload_crc,
                calculated: calculated_payload_crc,
            });
        }

        Ok(Some(Frame { msg_type, payload }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_frame_roundtrip() {
        let original = Frame::new(MessageType::ChunkData, b"High-speed binary chunk payload".to_vec());
        let encoded = original.encode();

        let mut buf = BytesMut::from(&encoded[..]);
        let decoded = Frame::decode(&mut buf).unwrap().expect("Should decode frame");

        assert_eq!(decoded.msg_type, MessageType::ChunkData);
        assert_eq!(decoded.payload, original.payload);
        assert!(buf.is_empty());
    }

    #[test]
    fn test_corrupted_payload_crc() {
        let original = Frame::new(MessageType::Ping, vec![1, 2, 3, 4]);
        let mut encoded = original.encode();
        // Corrupt one payload byte
        encoded[17] ^= 0xFF;

        let mut buf = BytesMut::from(&encoded[..]);
        assert!(matches!(
            Frame::decode(&mut buf),
            Err(FrameError::PayloadCrcMismatch { .. })
        ));
    }
}
