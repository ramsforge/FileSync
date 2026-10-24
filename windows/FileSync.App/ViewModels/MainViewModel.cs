using System.Collections.ObjectModel;
using System.IO;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using FileSync.App.Models;
using FileSync.App.Native;
using FileSync.App.Services;
using Microsoft.Win32;

namespace FileSync.App.ViewModels;

public partial class MainViewModel : ObservableObject
{
    private readonly AdbService _adbService;
    private readonly ExplorerMenuService _explorerMenuService;
    private readonly TransferClient _transferClient;
    private readonly TransferServer _transferServer;
    private CancellationTokenSource? _transferCts;

    [ObservableProperty]
    private string _coreVersion = "Loading...";

    [ObservableProperty]
    private bool _isTransferring = false;

    [ObservableProperty]
    private double _totalProgress = 0.0;

    [ObservableProperty]
    private string _currentSpeedText = "0 MB/s";

    [ObservableProperty]
    private string _transferredBytesText = "0 MB / 0 MB";

    [ObservableProperty]
    private string _etaText = "--";

    [ObservableProperty]
    private string _statusMessage = "Ready for high-speed file sharing.";

    [ObservableProperty]
    private bool _isContextMenuEnabled;

    [ObservableProperty]
    private PeerDevice? _selectedDevice;

    public ObservableCollection<PeerDevice> AvailableDevices { get; } = new();
    public ObservableCollection<TransferItem> Queue { get; } = new();

    public MainViewModel()
    {
        App.Trace("MainViewModel..ctor started");
        _adbService = new AdbService();
        _explorerMenuService = new ExplorerMenuService();
        _transferClient = new TransferClient();
        _transferServer = new TransferServer();

        _transferServer.StatusChanged += msg =>
        {
            App.Current?.Dispatcher.Invoke(() => StatusMessage = msg);
        };
        _transferServer.FileReceived += (name, path) =>
        {
            App.Current?.Dispatcher.Invoke(() => StatusMessage = $"Received: {name}");
        };
        _transferServer.Start(45781);

        App.Trace("MainViewModel..ctor calling GetCoreVersion");
        CoreVersion = CoreBridge.GetCoreVersion();
        App.Trace($"MainViewModel..ctor CoreVersion={CoreVersion}");

        IsContextMenuEnabled = _explorerMenuService.IsContextMenuRegistered();
        App.Trace("MainViewModel..ctor calling ScanDevices");

        // Seed with local discovery placeholder / USB status
        ScanDevices();
        App.Trace("MainViewModel..ctor completed");
    }

    [RelayCommand]
    public void ScanDevices()
    {
        AvailableDevices.Clear();

        // Check for USB ADB devices
        _ = CheckUsbDevicesAsync();

        // Add LAN discovered device placeholder
        AvailableDevices.Add(new PeerDevice
        {
            Name = "Pixel Phone (LAN)",
            OsName = "Android",
            EndPoint = "192.168.1.150:45780",
            ConnectionType = "Wi-Fi LAN"
        });

        if (AvailableDevices.Count > 0 && SelectedDevice == null)
        {
            SelectedDevice = AvailableDevices[0];
        }
    }

    private async Task CheckUsbDevicesAsync()
    {
        try
        {
            var devices = await _adbService.GetConnectedDevicesAsync();
            if (devices.Count == 0) return;

            void UpdateList()
            {
                foreach (var dev in devices)
                {
                    if (!AvailableDevices.Any(d => d.EndPoint == "127.0.0.1:45782"))
                    {
                        AvailableDevices.Insert(0, new PeerDevice
                        {
                            Name = $"Samsung Device ({dev})",
                            OsName = "Android",
                            EndPoint = "127.0.0.1:45782",
                            ConnectionType = "USB ADB Tunnel"
                        });
                    }
                }
                if (AvailableDevices.Count > 0)
                {
                    SelectedDevice = AvailableDevices[0];
                }
            }

            var dispatcher = App.Current?.Dispatcher;
            if (dispatcher != null)
            {
                if (dispatcher.CheckAccess())
                {
                    UpdateList();
                }
                else
                {
                    dispatcher.Invoke(UpdateList);
                }
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("Error checking USB devices: " + ex);
        }
    }

    [RelayCommand]
    public async Task ConnectUsbAdbAsync()
    {
        StatusMessage = "Setting up ADB high-speed USB tunnels...";
        var forwardOk = await _adbService.SetupAdbForwardAsync(45782, 45780);
        var reverseOk = await _adbService.SetupAdbReverseAsync(45783, 45781);
        if (forwardOk || reverseOk)
        {
            StatusMessage = "ADB High-Speed USB Tunnels established (Forward: 45782, Reverse: 45783)!";
            await CheckUsbDevicesAsync();
        }
        else
        {
            StatusMessage = "Could not setup ADB tunnels. Ensure USB debugging is enabled on tablet.";
        }
    }

    public void AddItemsFromPaths(IEnumerable<string> paths)
    {
        foreach (var path in paths)
        {
            if (File.Exists(path))
            {
                var fileInfo = new FileInfo(path);
                Queue.Add(new TransferItem
                {
                    FullPath = path,
                    Name = fileInfo.Name,
                    RelativePath = fileInfo.Name,
                    Size = fileInfo.Length,
                    IsDirectory = false
                });
            }
            else if (Directory.Exists(path))
            {
                var dirInfo = new DirectoryInfo(path);
                Queue.Add(new TransferItem
                {
                    FullPath = path,
                    Name = dirInfo.Name,
                    RelativePath = dirInfo.Name,
                    Size = 0,
                    IsDirectory = true
                });

                // Enumerate children recursively
                foreach (var file in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
                {
                    var fileInfo = new FileInfo(file);
                    var relative = Path.GetRelativePath(dirInfo.Parent?.FullName ?? dirInfo.FullName, file);
                    Queue.Add(new TransferItem
                    {
                        FullPath = file,
                        Name = fileInfo.Name,
                        RelativePath = relative.Replace('\\', '/'),
                        Size = fileInfo.Length,
                        IsDirectory = false
                    });
                }
            }
        }

        UpdateQueueTotals();
    }

    [RelayCommand]
    public void AddFiles()
    {
        var openFileDialog = new OpenFileDialog
        {
            Multiselect = true,
            Title = "Select Files to Send"
        };

        if (openFileDialog.ShowDialog() == true)
        {
            AddItemsFromPaths(openFileDialog.FileNames);
        }
    }

    [RelayCommand]
    public void ClearQueue()
    {
        Queue.Clear();
        UpdateQueueTotals();
    }

    private void UpdateQueueTotals()
    {
        long totalBytes = Queue.Where(i => !i.IsDirectory).Sum(i => i.Size);
        TransferredBytesText = $"0 MB / {FormatBytes(totalBytes)}";
        StatusMessage = $"{Queue.Count} item(s) queued ({FormatBytes(totalBytes)} total).";
    }

    [RelayCommand]
    public async Task StartTransferAsync()
    {
        if (Queue.Count == 0)
        {
            StatusMessage = "Transfer queue is empty. Drag and drop files to send.";
            return;
        }

        if (SelectedDevice == null)
        {
            StatusMessage = "No target Android device selected.";
            return;
        }

        IsTransferring = true;
        _transferCts = new CancellationTokenSource();

        string host;
        int port;

        if (SelectedDevice.EndPoint.Contains("127.0.0.1") || SelectedDevice.ConnectionType.Contains("USB"))
        {
            // USB Mode: ensure ADB forward port is active
            await _adbService.SetupAdbForwardAsync(45782, 45780);
            host = "127.0.0.1";
            port = 45782;
            StatusMessage = $"Connecting to {SelectedDevice.Name} via USB ADB tunnel (port {port})...";
        }
        else
        {
            var parts = SelectedDevice.EndPoint.Split(':');
            host = parts[0];
            port = parts.Length > 1 ? int.Parse(parts[1]) : 45780;
            StatusMessage = $"Connecting to {SelectedDevice.Name} via Wi-Fi LAN ({host}:{port})...";
        }

        try
        {
            var itemsToSend = Queue.ToList();
            bool success = await _transferClient.SendFilesAsync(
                host,
                port,
                itemsToSend,
                report =>
                {
                    App.Current?.Dispatcher.Invoke(() =>
                    {
                        if (report.CurrentFileIndex >= 0 && report.CurrentFileIndex < Queue.Count)
                        {
                            var qItem = Queue[report.CurrentFileIndex];
                            qItem.Progress = report.CurrentFileProgress;
                            qItem.Status = report.CurrentFileProgress >= 100.0 ? "Completed" : "InProgress";
                        }
                        TotalProgress = report.TotalProgress;
                        CurrentSpeedText = report.FormattedSpeed;
                        TransferredBytesText = $"{FormatBytes(report.TotalBytesTransferred)} / {FormatBytes(report.TotalBytes)}";
                        EtaText = report.FormattedEta;
                        if (!string.IsNullOrEmpty(report.StatusMessage))
                        {
                            StatusMessage = report.StatusMessage;
                        }
                    });
                },
                _transferCts.Token);

            if (success)
            {
                App.Current?.Dispatcher.Invoke(() =>
                {
                    foreach (var item in Queue)
                    {
                        item.Progress = 100.0;
                        item.Status = "Completed";
                    }
                    TotalProgress = 100.0;
                    StatusMessage = $"✅ Successfully transferred {Queue.Count} file(s) to Android!";
                });

                System.Windows.MessageBox.Show(
                    $"Successfully transferred {Queue.Count} file(s) to your Android device!\n\nSaved to: Internal Storage > Download > FileSync",
                    "FileSync Transfer Complete",
                    System.Windows.MessageBoxButton.OK,
                    System.Windows.MessageBoxImage.Information);
            }
        }
        catch (OperationCanceledException)
        {
            StatusMessage = "Transfer was cancelled by user.";
        }
        catch (Exception ex)
        {
            StatusMessage = $"Transfer error: {ex.Message}";
        }
        finally
        {
            IsTransferring = false;
            CurrentSpeedText = "0 MB/s";
            _transferCts = null;
        }
    }

    [RelayCommand]
    public void CancelTransfer()
    {
        _transferCts?.Cancel();
        IsTransferring = false;
        StatusMessage = "Transfer cancelled.";
        CurrentSpeedText = "0 MB/s";
    }

    [RelayCommand]
    public void ToggleContextMenu()
    {
        if (IsContextMenuEnabled)
        {
            _explorerMenuService.UnregisterContextMenu();
            IsContextMenuEnabled = false;
            StatusMessage = "Removed Explorer context menu.";
        }
        else
        {
            _explorerMenuService.RegisterContextMenu();
            IsContextMenuEnabled = true;
            StatusMessage = "Added 'Send with FileSync' to Windows Explorer context menu.";
        }
    }

    private string FormatBytes(long bytes)
    {
        string[] sizes = { "B", "KB", "MB", "GB", "TB" };
        double len = bytes;
        int order = 0;
        while (len >= 1024 && order < sizes.Length - 1)
        {
            order++;
            len /= 1024;
        }
        return $"{len:0.##} {sizes[order]}";
    }
}
