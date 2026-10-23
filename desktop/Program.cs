using System;
using System.Windows.Forms;

namespace UltraUpscale.ControlCenter;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
