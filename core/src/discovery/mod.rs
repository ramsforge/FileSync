use std::net::SocketAddr;
use serde::{Deserialize, Serialize};

pub const DISCOVERY_PORT: u16 = 45781;
pub const DEFAULT_SERVICE_PORT: u16 = 45780;
pub const DISCOVERY_MAGIC: &[u8; 14] = b"FSYNC_DISCOVER";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PeerBeacon {
    pub magic: [u8; 14],
    pub device_name: String,
    pub os_name: String,
    pub service_port: u16,
    pub device_id: [u8; 16],
}

impl PeerBeacon {
    pub fn new(device_name: String, os_name: String, service_port: u16, device_id: [u8; 16]) -> Self {
        Self {
            magic: *DISCOVERY_MAGIC,
            device_name,
            os_name,
            service_port,
            device_id,
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        bincode::serialize(self).unwrap_or_default()
    }

    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let beacon: PeerBeacon = bincode::deserialize(bytes).ok()?;
        if beacon.magic == *DISCOVERY_MAGIC {
            Some(beacon)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredPeer {
    pub device_name: String,
    pub os_name: String,
    pub addr: SocketAddr,
    pub device_id: [u8; 16],
}
