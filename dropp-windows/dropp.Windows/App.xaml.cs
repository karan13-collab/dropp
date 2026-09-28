using dropp.Windows.Core.Networking;
using Microsoft.UI.Xaml;
using System.Diagnostics;
using System.Threading.Tasks;

namespace dropp.Windows // Ensure this perfectly matches the x:Class in App.xaml
{
    public partial class App : Application
    {
        // Nullable types (?) resolve the CS8618 warnings
        private Window? m_window;
        private MdnsManager? _mdnsManager;

        public App()
        {
            this.InitializeComponent();
        }

        protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
        {
            m_window = new MainWindow();
            m_window.Activate();

            _mdnsManager = new MdnsManager();

            // Fire and forget the background network discovery
            _ = Task.Run(async () =>
            {
                Debug.WriteLine("Starting mDNS discovery and broadcast for dropp ecosystem...");
                _mdnsManager.StartBroadcasting();
                await _mdnsManager.StartListeningAsync();
            });
        }
    }
}