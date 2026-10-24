using CommunityToolkit.Mvvm.ComponentModel;

namespace FileSync.App.Models;

public partial class TransferItem : ObservableObject
{
    public string FullPath { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string RelativePath { get; set; } = string.Empty;
    public long Size { get; set; }
    public bool IsDirectory { get; set; }

    [ObservableProperty]
    private string _status = "Pending"; // Pending, InProgress, Completed, Failed, Skipped

    [ObservableProperty]
    private double _progress = 0.0;

    public string FormattedSize
    {
        get
        {
            if (IsDirectory) return "Directory";
            string[] sizes = { "B", "KB", "MB", "GB", "TB" };
            double len = Size;
            int order = 0;
            while (len >= 1024 && order < sizes.Length - 1)
            {
                order++;
                len /= 1024;
            }
            return $"{len:0.##} {sizes[order]}";
        }
    }
}
