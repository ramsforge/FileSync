using System.Runtime.InteropServices;

namespace FileSync.App.Native;

public static class CoreBridge
{
    private const string DllName = "filesync_core.dll";

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate void ProgressCallback(
        ulong currentFileIndex,
        ulong totalFiles,
        ulong bytesTransferred,
        ulong totalBytes,
        ulong speedBytesPerSec
    );

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate void StatusCallback(int statusCode, IntPtr messagePtr);

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr filesync_version();

    [DllImport(DllName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void filesync_free_string(IntPtr ptr);

    public static string GetCoreVersion()
    {
        try
        {
            var ptr = filesync_version();
            if (ptr == IntPtr.Zero) return "Unknown";
            var ver = Marshal.PtrToStringUTF8(ptr) ?? "Unknown";
            filesync_free_string(ptr);
            return ver;
        }
        catch (DllNotFoundException)
        {
            return "DLL not found (ensure build_core.ps1 was run)";
        }
        catch (Exception ex)
        {
            return $"Error: {ex.Message}";
        }
    }
}
