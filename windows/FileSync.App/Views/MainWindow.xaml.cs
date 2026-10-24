using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace FileSync.App.Views;

public partial class MainWindow
{
    public MainWindow()
    {
        InitializeComponent();

        // Process any command line arguments (e.g. from Explorer right-click context menu)
        var args = Environment.GetCommandLineArgs();
        if (args.Length > 1)
        {
            var files = args.Skip(1).Where(System.IO.File.Exists).ToList();
            var dirs = args.Skip(1).Where(System.IO.Directory.Exists).ToList();
            var all = files.Concat(dirs);
            if (all.Any())
            {
                ViewModel.AddItemsFromPaths(all);
            }
        }
    }

    private void DropZone_DragOver(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            e.Effects = DragDropEffects.Copy;
            if (sender is Border border)
            {
                border.BorderBrush = new SolidColorBrush(Color.FromRgb(0x4C, 0xC2, 0xFF));
                border.Background = new SolidColorBrush(Color.FromArgb(0x20, 0x4C, 0xC2, 0xFF));
            }
        }
        else
        {
            e.Effects = DragDropEffects.None;
        }
        e.Handled = true;
    }

    private void DropZone_DragLeave(object sender, DragEventArgs e)
    {
        if (sender is Border border)
        {
            border.BorderBrush = new SolidColorBrush(Color.FromArgb(0x40, 0xFF, 0xFF, 0xFF));
            border.Background = new SolidColorBrush(Color.FromArgb(0x0C, 0xFF, 0xFF, 0xFF));
        }
    }

    private void DropZone_Drop(object sender, DragEventArgs e)
    {
        DropZone_DragLeave(sender, e);

        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            if (files != null && files.Length > 0)
            {
                ViewModel.AddItemsFromPaths(files);
            }
        }
    }
}
