using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;
using System;
using System.Collections.Generic;
using System.ComponentModel.Design;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Windows.Input;
using Windows.Foundation;
using Windows.Foundation.Collections;
using Windows.Storage.Pickers;

namespace dropp.Windows
{
    public sealed partial class MainWindow : Window
    {
        private AppWindow _appWindow;

        // 1. MUST BE AT CLASS LEVEL: Define the commands so XAML can see them
        public ICommand TrayDoubleClickCommand { get; }
        public ICommand OpenAppCommand { get; }
        public ICommand QuitAppCommand { get; }

        public MainWindow()
        {
            // 2. Initialize the commands
            TrayDoubleClickCommand = new StandardCommand(() =>
            {
                _appWindow?.Show(); // Added ? to fix the null warning
                this.Activate();
            });

            OpenAppCommand = new StandardCommand(() =>
            {
                _appWindow?.Show();
                this.Activate();
            });

            QuitAppCommand = new StandardCommand(() =>
            {
                Environment.Exit(0);
            });

            this.InitializeComponent();
            ShowPairingQrCode();

            // 3. Setup the AppWindow
            IntPtr hWnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            Microsoft.UI.WindowId windowId = Microsoft.UI.Win32Interop.GetWindowIdFromWindow(hWnd);
            _appWindow = AppWindow.GetFromWindowId(windowId);

            _appWindow.Closing += AppWindow_Closing;
        }

        private void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
        {
            args.Cancel = true;
            _appWindow?.Hide();
        }

        private void ShowPairingQrCode()
        {
            QRCodeGenerator qrGenerator = new QRCodeGenerator();
            QRCodeData qrCodeData = qrGenerator.CreateQrCode(App.MasterKey, QRCodeGenerator.ECCLevel.L);
            PngByteQRCode qrCode = new PngByteQRCode(qrCodeData);
            byte[] qrCodeBytes = qrCode.GetGraphic(20);

            string tempFile = Path.Combine(global::Windows.Storage.ApplicationData.Current.TemporaryFolder.Path, "pairing_qr.png");
            File.WriteAllBytes(tempFile, qrCodeBytes);

            QrCodeImage.Source = new BitmapImage(new Uri(tempFile));
        }

        private async void TestSendFile_Click(object sender, RoutedEventArgs e)
        {
            // 1. Create the File Picker
            var picker = new FileOpenPicker();
            picker.ViewMode = PickerViewMode.Thumbnail;
            picker.SuggestedStartLocation = PickerLocationId.Desktop;
            picker.FileTypeFilter.Add("*"); // Allow any file type

            // 2. Attach the picker to this specific app window (Required for WinUI 3)
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

            // 3. Open the picker and wait for the user to select a file
            var file = await picker.PickSingleFileAsync();

            if (file != null)
            {
                // 4. Send the file they actually picked!
                System.Diagnostics.Debug.WriteLine($"[UI] User selected: {file.Name}");
                await App.FileTransfer.SendFileAsync(file.Path);
            }
            else
            {
                System.Diagnostics.Debug.WriteLine("[UI] User canceled file selection.");
            }
        }
    }

    // Command Helper Class
    public class StandardCommand : ICommand
    {
        private readonly Action _action;
        public StandardCommand(Action action) { _action = action; }

#pragma warning disable CS0067 // Safely ignores the "event never used" warning
        public event EventHandler? CanExecuteChanged;
#pragma warning restore CS0067

        public bool CanExecute(object? parameter) => true;
        public void Execute(object? parameter) => _action();
    }
}