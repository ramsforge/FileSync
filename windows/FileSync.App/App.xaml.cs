using System;
using System.IO;
using System.Windows;
using System.Windows.Threading;

namespace FileSync.App;

public partial class App : Application
{
    private static readonly string LogPath = @"C:\FileSync\startup_trace.log";

    public static void Trace(string msg)
    {
        try
        {
            File.AppendAllText(LogPath, $"[{DateTime.Now:HH:mm:ss.fff}] {msg}\r\n");
        }
        catch { }
    }

    public App()
    {
        Trace("App..ctor started");
        DispatcherUnhandledException += App_DispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += CurrentDomain_UnhandledException;
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        Trace("App.OnStartup started");
        base.OnStartup(e);
        Trace("App.OnStartup finished base call");
    }

    private void App_DispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Trace($"Dispatcher Exception: {e.Exception}");
        MessageBox.Show(e.Exception.ToString(), "FileSync Startup Error", MessageBoxButton.OK, MessageBoxImage.Error);
        e.Handled = true;
    }

    private void CurrentDomain_UnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        Trace($"Domain Exception: {e.ExceptionObject}");
        MessageBox.Show(e.ExceptionObject?.ToString() ?? "Unknown error", "FileSync Fatal Error", MessageBoxButton.OK, MessageBoxImage.Error);
    }
}
