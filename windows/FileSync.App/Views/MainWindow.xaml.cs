using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace FileSync.App.Views;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        App.Trace("MainWindow..ctor started");
        try
        {
            InitializeComponent();
            App.Trace("MainWindow.InitializeComponent finished");
        }
        catch (Exception ex)
        {
            App.Trace($"MainWindow.InitializeComponent EXCEPTION: {ex}");
            throw;
        }

        Loaded += (s, e) => App.Trace("MainWindow.Loaded fired");

        var args = Environment.GetCommandLineArgs();
        if (args.Length > 1)
        {
            var files = args.Skip(1).Where(File.Exists).ToList();
            var dirs = args.Skip(1).Where(Directory.Exists).ToList();
            var all = files.Concat(dirs);
            if (all.Any())
            {
                ViewModel.AddItemsFromPaths(all);
            }
        }
        App.Trace("MainWindow..ctor completed");
    }

    private void OpenReceivedFolder_Click(object sender, RoutedEventArgs e)
    {
        App.Trace("OpenReceivedFolder_Click fired");
        ViewModel.OpenReceivedFolder();
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
            border.Background = new SolidColorBrush(Color.FromRgb(0x1E, 0x1E, 0x1E));
        }
    }

    private void DropZone_Drop(object sender, DragEventArgs e)
    {
        DropZone_DragLeave(sender, e);

        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var paths = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            if (paths != null && paths.Length > 0)
            {
                ViewModel.AddItemsFromPaths(paths);
            }
        }
    }
}
