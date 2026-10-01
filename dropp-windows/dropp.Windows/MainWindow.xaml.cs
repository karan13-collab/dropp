using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.Foundation;
using Windows.Foundation.Collections;
using Windows.Storage.Pickers;

// To learn more about WinUI, the WinUI project structure,
// and more about our project templates, see: http://aka.ms/winui-project-info.

namespace dropp.Windows
{
    /// <summary>
    /// An empty window that can be used on its own or navigated to within a Frame.
    /// </summary>
    public sealed partial class MainWindow : Window
    {
        public MainWindow()
        {
            InitializeComponent();
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
}
