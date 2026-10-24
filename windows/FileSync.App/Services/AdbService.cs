using System.Diagnostics;
using System.IO;

namespace FileSync.App.Services;

public class AdbService
{
    private string? _adbPath;

    public string? GetAdbPath()
    {
        if (_adbPath != null && File.Exists(_adbPath)) return _adbPath;

        // 1. Check application directory
        var localAdb = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "adb.exe");
        if (File.Exists(localAdb))
        {
            _adbPath = localAdb;
            return _adbPath;
        }

        // 2. Check Android SDK default path
        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var sdkAdb = Path.Combine(localAppData, "Android", "Sdk", "platform-tools", "adb.exe");
        if (File.Exists(sdkAdb))
        {
            _adbPath = sdkAdb;
            return _adbPath;
        }

        // 3. Check system PATH
        var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in pathEnv.Split(Path.PathSeparator))
        {
            try
            {
                var candidate = Path.Combine(dir.Trim(), "adb.exe");
                if (File.Exists(candidate))
                {
                    _adbPath = candidate;
                    return _adbPath;
                }
            }
            catch
            {
                // Ignore invalid paths in PATH env
            }
        }

        return null;
    }

    public async Task<List<string>> GetConnectedDevicesAsync()
    {
        var adb = GetAdbPath();
        if (adb == null) return new List<string>();

        try
        {
            var output = await RunAdbAsync("devices");
            var devices = new List<string>();
            var lines = output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);

            foreach (var line in lines)
            {
                if (line.StartsWith("List of devices")) continue;
                var parts = line.Split('\t', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && parts[1].Trim() == "device")
                {
                    devices.Add(parts[0].Trim());
                }
            }

            return devices;
        }
        catch
        {
            return new List<string>();
        }
    }

    public async Task<bool> SetupAdbReverseAsync(int port = 45780)
    {
        var adb = GetAdbPath();
        if (adb == null) return false;

        try
        {
            var output = await RunAdbAsync($"reverse tcp:{port} tcp:{port}");
            return !output.Contains("error", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private async Task<string> RunAdbAsync(string arguments)
    {
        var adb = GetAdbPath();
        if (adb == null) throw new FileNotFoundException("adb.exe was not found.");

        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = adb,
                Arguments = arguments,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            }
        };

        process.Start();
        var output = await process.StandardOutput.ReadToEndAsync();
        await process.WaitForExitAsync();
        return output;
    }
}
