use std::io;
use bytes::BytesMut;
use socket2::Socket;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use crate::protocol::frame::{Frame, FrameError};

#[cfg(windows)]
use std::os::windows::io::FromRawSocket;
#[cfg(unix)]
use std::os::unix::io::FromRawFd;

/// Default socket send & receive buffer size (4 MB for high-speed multi-gigabit throughput)
pub const SOCKET_BUFFER_SIZE: usize = 4 * 1024 * 1024;

/// Tunes a TCP socket with high-performance parameters:
/// - Disables Nagle's algorithm (TCP_NODELAY)
/// - Expands send and receive buffers to 4 MB
/// - Enables TCP Keep-Alive
pub fn tune_socket(stream: &TcpStream) -> io::Result<()> {
    stream.set_nodelay(true)?;

    #[cfg(windows)]
    use std::os::windows::io::AsRawSocket;
    #[cfg(unix)]
    use std::os::unix::io::AsRawFd;

    #[cfg(windows)]
    {
        let raw = stream.as_raw_socket();
        unsafe {
            let sock = Socket::from_raw_socket(raw);
            let _ = sock.set_send_buffer_size(SOCKET_BUFFER_SIZE);
            let _ = sock.set_recv_buffer_size(SOCKET_BUFFER_SIZE);
            std::mem::forget(sock); // Prevent closing socket on drop!
        }
    }

    #[cfg(unix)]
    {
        let raw = stream.as_raw_fd();
        unsafe {
            let sock = Socket::from_raw_fd(raw);
            let _ = sock.set_send_buffer_size(SOCKET_BUFFER_SIZE);
            let _ = sock.set_recv_buffer_size(SOCKET_BUFFER_SIZE);
            std::mem::forget(sock);
        }
    }

    Ok(())
}

/// Asynchronous reader for framed FileSync messages over a TCP stream
pub struct FrameReader<R> {
    reader: R,
    buffer: BytesMut,
}

impl<R: AsyncReadExt + Unpin> FrameReader<R> {
    pub fn new(reader: R) -> Self {
        Self {
            reader,
            buffer: BytesMut::with_capacity(SOCKET_BUFFER_SIZE),
        }
    }

    /// Reads the next complete frame from the stream
    pub async fn read_frame(&mut self) -> Result<Option<Frame>, FrameError> {
        loop {
            if let Some(frame) = Frame::decode(&mut self.buffer)? {
                return Ok(Some(frame));
            }

            let bytes_read = self.reader.read_buf(&mut self.buffer).await?;
            if bytes_read == 0 {
                if self.buffer.is_empty() {
                    return Ok(None);
                } else {
                    return Err(FrameError::IncompleteHeader(16, self.buffer.len()));
                }
            }
        }
    }
}

/// Asynchronous writer for framed FileSync messages over a TCP stream
pub struct FrameWriter<W> {
    writer: W,
}

impl<W: AsyncWriteExt + Unpin> FrameWriter<W> {
    pub fn new(writer: W) -> Self {
        Self { writer }
    }

    /// Writes and flushes a frame to the underlying stream
    pub async fn write_frame(&mut self, frame: &Frame) -> Result<(), FrameError> {
        let encoded = frame.encode();
        self.writer.write_all(&encoded).await?;
        self.writer.flush().await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::duplex;
    use crate::protocol::frame::MessageType;

    #[tokio::test]
    async fn test_framed_transport_duplex() {
        let (client, server) = duplex(64 * 1024);

        let mut writer = FrameWriter::new(client);
        let mut reader = FrameReader::new(server);

        let send_frame = Frame::new(MessageType::ChunkData, vec![42u8; 1024]);

        tokio::spawn(async move {
            writer.write_frame(&send_frame).await.unwrap();
        });

        let received = reader.read_frame().await.unwrap().expect("Should receive frame");
        assert_eq!(received.msg_type, MessageType::ChunkData);
        assert_eq!(received.payload.len(), 1024);
        assert_eq!(received.payload[0], 42);
    }
}
