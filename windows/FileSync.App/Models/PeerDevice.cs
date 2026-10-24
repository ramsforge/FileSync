namespace FileSync.App.Models;

public class PeerDevice
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = "Android Device";
    public string OsName { get; set; } = "Android";
    public string EndPoint { get; set; } = "192.168.1.100:45780";
    public string ConnectionType { get; set; } = "Wi-Fi LAN"; // "Wi-Fi LAN", "USB Tethering", "USB ADB"
    public bool IsPaired { get; set; } = true;

    public string DisplayIcon => ConnectionType.Contains("USB") ? "Usb24" : "Wifi24";
}
