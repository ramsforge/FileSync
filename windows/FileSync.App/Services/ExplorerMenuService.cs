using Microsoft.Win32;
using System.Diagnostics;

namespace FileSync.App.Services;

public class ExplorerMenuService
{
    private const string MenuKeyName = "FileSync";
    private const string MenuText = "Send with FileSync";

    public bool IsContextMenuRegistered()
    {
        try
        {
            using var fileKey = Registry.CurrentUser.OpenSubKey(@"Software\Classes\*\shell\" + MenuKeyName);
            using var dirKey = Registry.CurrentUser.OpenSubKey(@"Software\Classes\Directory\shell\" + MenuKeyName);
            return fileKey != null && dirKey != null;
        }
        catch
        {
            return false;
        }
    }

    public void RegisterContextMenu()
    {
        var exePath = Process.GetCurrentProcess().MainModule?.FileName;
        if (string.IsNullOrEmpty(exePath)) return;

        var command = $"\"{exePath}\" \"%1\"";

        // Register for all files: HKCU\Software\Classes\*\shell\FileSync
        using (var key = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\" + MenuKeyName))
        {
            key.SetValue("", MenuText);
            key.SetValue("Icon", exePath);
            using var cmdKey = key.CreateSubKey("command");
            cmdKey.SetValue("", command);
        }

        // Register for all directories: HKCU\Software\Classes\Directory\shell\FileSync
        using (var key = Registry.CurrentUser.CreateSubKey(@"Software\Classes\Directory\shell\" + MenuKeyName))
        {
            key.SetValue("", MenuText);
            key.SetValue("Icon", exePath);
            using var cmdKey = key.CreateSubKey("command");
            cmdKey.SetValue("", command);
        }
    }

    public void UnregisterContextMenu()
    {
        try
        {
            Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\" + MenuKeyName, false);
            Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\Directory\shell\" + MenuKeyName, false);
        }
        catch
        {
            // Ignore if subkeys do not exist
        }
    }
}
