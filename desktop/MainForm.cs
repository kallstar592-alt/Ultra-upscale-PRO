using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace UltraUpscale.ControlCenter;

internal sealed class MainForm : Form
{
    private static readonly Color Background = Color.FromArgb(16, 18, 23);
    private static readonly Color CardColor = Color.FromArgb(27, 30, 37);
    private static readonly Color CardAlt = Color.FromArgb(35, 39, 48);
    private static readonly Color Accent = Color.FromArgb(166, 255, 80);
    private static readonly Color Foreground = Color.FromArgb(244, 246, 250);
    private static readonly Color Muted = Color.FromArgb(164, 171, 184);
    private static readonly Color Warning = Color.FromArgb(255, 190, 83);

    private const int EnumCurrentSettings = -1;
    private const uint CdsTest = 0x00000002;
    private const uint CdsFullscreen = 0x00000004;
    private const int DmBitsPerPel = 0x00040000;
    private const int DmPelsWidth = 0x00080000;
    private const int DmPelsHeight = 0x00100000;
    private const int DmDisplayFrequency = 0x00400000;
    private const int DisplayChangeSuccessful = 0;

    private readonly List<TargetPreset> _presets = new()
    {
        new TargetPreset("4k", "4K UHD", 3840, 2160),
        new TargetPreset("5k", "5K", 5120, 2880),
        new TargetPreset("8k", "8K UHD", 7680, 4320),
        new TargetPreset("10k", "10K", 10240, 5760),
        new TargetPreset("16k", "16K", 15360, 8640)
    };

    private readonly List<DEVMODE> _availableModes = new();
    private readonly ComboBox _targetPicker = new();
    private readonly Label _currentResolutionLabel = new();
    private readonly Label _availabilityLabel = new();
    private readonly Label _boostStateLabel = new();
    private readonly Label _statusLabel = new();
    private readonly NotifyIcon _trayIcon;
    private readonly ContextMenuStrip _trayMenu;
    private readonly Button _applyButton;
    private readonly Button _toggleButton;
    private bool _isRefreshing;
    private bool _allowExit;
    private bool _boostEnabled;
    private DEVMODE? _baselineMode;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool EnumDisplaySettings(string? deviceName, int modeNum, ref DEVMODE devMode);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int ChangeDisplaySettings(ref DEVMODE devMode, uint flags);

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    private sealed class TargetPreset
    {
        public string Id { get; }
        public string Label { get; }
        public int Width { get; }
        public int Height { get; }
        public DEVMODE? SupportedMode { get; set; }

        public TargetPreset(string id, string label, int width, int height)
        {
            Id = id;
            Label = label;
            Width = width;
            Height = height;
        }

        public override string ToString() =>
            $"{Label}  ·  {Width:N0} × {Height:N0}  ·  {(SupportedMode.HasValue ? "AVAILABLE" : "NOT EXPOSED BY GPU")}";
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string dmDeviceName;
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public int dmDisplayOrientation;
        public int dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
        public int dmICMMethod;
        public int dmICMIntent;
        public int dmMediaType;
        public int dmDitherType;
        public int dmReserved1;
        public int dmReserved2;
        public int dmPanningWidth;
        public int dmPanningHeight;
    }

    public MainForm()
    {
        Text = "Ultra Upscale Control Center";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Size = new Size(474, 760);
        MinimumSize = new Size(474, 620);
        MaximumSize = new Size(474, 900);
        BackColor = Background;
        ForeColor = Foreground;
        Font = new Font("Segoe UI", 9F, FontStyle.Regular);
        ShowInTaskbar = false;
        TopMost = true;
        DoubleBuffered = true;

        var header = BuildHeader();
        var page = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            BackColor = Background,
            Padding = new Padding(16, 12, 16, 16)
        };
        Controls.Add(page);
        Controls.Add(header);

        _toggleButton = MakeButton("ENABLE SCREEN BOOST", true, 384, 42);
        _toggleButton.Click += (_, _) => ToggleBoost();
        page.Controls.Add(BuildBoostCard());

        var targetCard = BuildTargetCard();
        _applyButton = MakeButton("APPLY TO ENTIRE WINDOWS DESKTOP", true, 384, 38);
        _applyButton.Location = new Point(16, 207);
        _applyButton.Click += (_, _) => ApplySelectedMode();
        targetCard.Controls.Add(_applyButton);
        page.Controls.Add(targetCard);

        page.Controls.Add(BuildDriverCard());
        page.Controls.Add(BuildStatusCard());

        _trayMenu = new ContextMenuStrip();
        _trayMenu.Items.Add("Open Control Center", null, (_, _) => ShowPanel());
        _trayMenu.Items.Add("Refresh supported modes", null, (_, _) => RefreshDisplayModes());
        _trayMenu.Items.Add("Open Windows Display Settings", null, (_, _) => OpenDisplaySettings());
        _trayMenu.Items.Add(new ToolStripSeparator());
        _trayMenu.Items.Add("Exit and restore previous resolution", null, (_, _) => ExitAndRestore());

        _trayIcon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Text = "Ultra Upscale Control Center",
            ContextMenuStrip = _trayMenu,
            Visible = true
        };
        _trayIcon.DoubleClick += (_, _) => ShowPanel();

        RefreshDisplayModes();
        PositionNearTaskbar();

        FormClosing += (_, e) =>
        {
            if (!_allowExit)
            {
                e.Cancel = true;
                Hide();
            }
        };
        Shown += (_, _) => PositionNearTaskbar();
    }

    private Panel BuildHeader()
    {
        var header = new Panel
        {
            Dock = DockStyle.Top,
            Height = 82,
            BackColor = Color.FromArgb(22, 25, 31)
        };
        header.MouseDown += DragHeader;

        var mark = new Label
        {
            Text = "U/",
            Font = new Font("Segoe UI", 18, FontStyle.Bold),
            ForeColor = Accent,
            AutoSize = true,
            Location = new Point(18, 15)
        };
        var title = new Label
        {
            Text = "ULTRA UPSCALE",
            Font = new Font("Segoe UI Semibold", 13, FontStyle.Bold),
            ForeColor = Foreground,
            AutoSize = true,
            Location = new Point(62, 16)
        };
        var subtitle = new Label
        {
            Text = "CONTROL CENTER  /  WINDOWS DESKTOP",
            Font = new Font("Segoe UI", 8, FontStyle.Bold),
            ForeColor = Muted,
            AutoSize = true,
            Location = new Point(63, 42)
        };
        title.MouseDown += DragHeader;
        subtitle.MouseDown += DragHeader;

        var minimize = MakeHeaderButton("—");
        minimize.Location = new Point(409, 9);
        minimize.Click += (_, _) => Hide();

        var close = MakeHeaderButton("×");
        close.Location = new Point(440, 9);
        close.Click += (_, _) => Hide();

        header.Controls.Add(mark);
        header.Controls.Add(title);
        header.Controls.Add(subtitle);
        header.Controls.Add(minimize);
        header.Controls.Add(close);
        return header;
    }

    private Panel BuildBoostCard()
    {
        var card = NewCard(144);
        card.Controls.Add(MakeLabel("SCREEN BOOST", 10, Accent, true, new Point(16, 13)));
        _boostStateLabel.Text = "OFF  ·  DISPLAY UNCHANGED";
        _boostStateLabel.Font = new Font("Segoe UI", 9, FontStyle.Bold);
        _boostStateLabel.ForeColor = Muted;
        _boostStateLabel.AutoSize = true;
        _boostStateLabel.Location = new Point(16, 38);
        card.Controls.Add(_boostStateLabel);
        card.Controls.Add(MakeLabel(
            "Changes Windows' desktop render mode globally when your GPU/display exposes the target.",
            9, Muted, false, new Point(16, 61), 382, 30));
        _toggleButton.Location = new Point(16, 96);
        card.Controls.Add(_toggleButton);
        return card;
    }

    private Panel BuildTargetCard()
    {
        var card = NewCard(255);
        card.Controls.Add(MakeLabel("GLOBAL DISPLAY RESOLUTION", 10, Accent, true, new Point(16, 13)));
        card.Controls.Add(MakeLabel("Choose a target actually available from your driver.", 9, Muted, false, new Point(16, 37), 382, 20));

        _targetPicker.Location = new Point(16, 62);
        _targetPicker.Size = new Size(384, 35);
        _targetPicker.DropDownStyle = ComboBoxStyle.DropDownList;
        _targetPicker.FlatStyle = FlatStyle.Flat;
        _targetPicker.BackColor = CardAlt;
        _targetPicker.ForeColor = Foreground;
        _targetPicker.Font = new Font("Segoe UI", 9);
        _targetPicker.SelectedIndexChanged += (_, _) => UpdateTargetStatus();
        card.Controls.Add(_targetPicker);

        _currentResolutionLabel.AutoSize = false;
        _currentResolutionLabel.Location = new Point(16, 101);
        _currentResolutionLabel.Size = new Size(384, 22);
        _currentResolutionLabel.ForeColor = Foreground;
        _currentResolutionLabel.Font = new Font("Segoe UI", 9, FontStyle.Bold);
        card.Controls.Add(_currentResolutionLabel);

        _availabilityLabel.AutoSize = false;
        _availabilityLabel.Location = new Point(16, 124);
        _availabilityLabel.Size = new Size(384, 57);
        _availabilityLabel.ForeColor = Warning;
        _availabilityLabel.Font = new Font("Segoe UI", 8.5f);
        card.Controls.Add(_availabilityLabel);

        var refresh = MakeButton("REFRESH MODES", false, 384, 30);
        refresh.Location = new Point(16, 178);
        refresh.Click += (_, _) => RefreshDisplayModes();
        card.Controls.Add(refresh);
        return card;
    }

    private Panel BuildDriverCard()
    {
        var card = NewCard(154);
        card.Controls.Add(MakeLabel("DRIVER SUPER RESOLUTION", 10, Accent, true, new Point(16, 13)));
        card.Controls.Add(MakeLabel(
            "8K–16K modes appear only when Windows/GPU drivers expose them. Enable NVIDIA DSR or AMD Virtual Super Resolution in your driver panel, then refresh.",
            9, Muted, false, new Point(16, 38), 382, 55));

        var nvidia = MakeButton("NVIDIA CONTROL PANEL", false, 184, 34);
        nvidia.Location = new Point(16, 103);
        nvidia.Click += (_, _) => OpenNvidiaPanel();
        var settings = MakeButton("DISPLAY SETTINGS", false, 184, 34);
        settings.Location = new Point(216, 103);
        settings.Click += (_, _) => OpenDisplaySettings();
        card.Controls.Add(nvidia);
        card.Controls.Add(settings);
        return card;
    }

    private Panel BuildStatusCard()
    {
        var card = NewCard(84);
        card.Controls.Add(MakeLabel("STATUS", 10, Accent, true, new Point(16, 13)));
        _statusLabel.AutoSize = false;
        _statusLabel.Location = new Point(16, 37);
        _statusLabel.Size = new Size(384, 35);
        _statusLabel.Text = "Reading supported Windows display modes…";
        _statusLabel.Font = new Font("Segoe UI", 9);
        _statusLabel.ForeColor = Muted;
        card.Controls.Add(_statusLabel);
        return card;
    }

    private static Panel NewCard(int height) => new()
    {
        Width = 416,
        Height = height,
        BackColor = CardColor,
        Margin = new Padding(0, 0, 0, 12),
        Padding = new Padding(0)
    };

    private static Label MakeLabel(string text, float size, Color color, bool bold, Point location, int maxWidth = 0, int height = 0)
    {
        var label = new Label
        {
            Text = text,
            Font = new Font(bold ? "Segoe UI Semibold" : "Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
            ForeColor = color,
            AutoSize = maxWidth == 0,
            Location = location,
            BackColor = Color.Transparent
        };
        if (maxWidth > 0)
            label.Size = new Size(maxWidth, height > 0 ? height : 30);
        return label;
    }

    private static Button MakeButton(string text, bool primary, int width, int height)
    {
        var button = new Button
        {
            Text = text,
            Width = width,
            Height = height,
            FlatStyle = FlatStyle.Flat,
            BackColor = primary ? Accent : CardAlt,
            ForeColor = primary ? Color.FromArgb(17, 22, 15) : Foreground,
            Font = new Font("Segoe UI", 8.5f, FontStyle.Bold),
            Cursor = Cursors.Hand,
            UseVisualStyleBackColor = false,
            Margin = new Padding(0)
        };
        button.FlatAppearance.BorderSize = 0;
        button.FlatAppearance.MouseOverBackColor = primary ? Color.FromArgb(190, 255, 128) : Color.FromArgb(47, 53, 64);
        button.FlatAppearance.MouseDownBackColor = primary ? Color.FromArgb(137, 224, 56) : Color.FromArgb(40, 44, 53);
        return button;
    }

    private static Button MakeHeaderButton(string text)
    {
        var button = new Button
        {
            Text = text,
            Size = new Size(27, 27),
            FlatStyle = FlatStyle.Flat,
            BackColor = CardAlt,
            ForeColor = Foreground,
            Font = new Font("Segoe UI", 10, FontStyle.Bold),
            Cursor = Cursors.Hand,
            UseVisualStyleBackColor = false
        };
        button.FlatAppearance.BorderSize = 0;
        return button;
    }

    private void DragHeader(object? sender, MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Left) return;
        ReleaseCapture();
        SendMessage(Handle, 0xA1, (IntPtr)0x2, IntPtr.Zero);
    }

    private void PositionNearTaskbar()
    {
        var area = Screen.PrimaryScreen?.WorkingArea;
        if (!area.HasValue) return;
        Location = new Point(
            Math.Max(area.Value.Left, area.Value.Right - Width - 12),
            Math.Max(area.Value.Top, area.Value.Bottom - Height - 12));
    }

    private static DEVMODE NewDevMode() => new()
    {
        dmDeviceName = new string('\0', 32),
        dmFormName = new string('\0', 32),
        dmSize = (short)Marshal.SizeOf<DEVMODE>()
    };

    private static DEVMODE? GetCurrentMode()
    {
        var mode = NewDevMode();
        return EnumDisplaySettings(null, EnumCurrentSettings, ref mode) ? mode : null;
    }

    private void RefreshDisplayModes()
    {
        _isRefreshing = true;
        try
        {
            var current = GetCurrentMode();
            _availableModes.Clear();
            for (int i = 0; i < 4000; i++)
            {
                var mode = NewDevMode();
                if (!EnumDisplaySettings(null, i, ref mode)) break;
                if (mode.dmPelsWidth <= 0 || mode.dmPelsHeight <= 0) continue;
                _availableModes.Add(mode);
            }

            foreach (var preset in _presets)
            {
                var matches = _availableModes
                    .Where(m => m.dmPelsWidth == preset.Width && m.dmPelsHeight == preset.Height)
                    .OrderBy(m => current.HasValue
                        ? Math.Abs(m.dmDisplayFrequency - current.Value.dmDisplayFrequency)
                        : Math.Abs(m.dmDisplayFrequency - 60))
                    .ThenByDescending(m => m.dmBitsPerPel)
                    .ToList();
                preset.SupportedMode = matches.Count == 0 ? null : matches[0];
            }

            var previousId = (_targetPicker.SelectedItem as TargetPreset)?.Id ?? "4k";
            _targetPicker.BeginUpdate();
            _targetPicker.Items.Clear();
            foreach (var preset in _presets) _targetPicker.Items.Add(preset);
            var index = _presets.FindIndex(p => p.Id == previousId);
            _targetPicker.SelectedIndex = index >= 0 ? index : 0;
            _targetPicker.EndUpdate();

            if (current.HasValue)
            {
                var mode = current.Value;
                _currentResolutionLabel.Text =
                    $"Current desktop: {mode.dmPelsWidth:N0} × {mode.dmPelsHeight:N0}  ·  {Math.Max(1, mode.dmDisplayFrequency)} Hz";
                _statusLabel.Text = $"Found {_availableModes.Select(m => (m.dmPelsWidth, m.dmPelsHeight)).Distinct().Count()} display modes. Only driver-exposed modes can be applied globally.";
            }
            else
            {
                _currentResolutionLabel.Text = "Current desktop mode unavailable";
                _statusLabel.Text = "Windows did not return the current display mode.";
            }
        }
        catch (Exception ex)
        {
            _statusLabel.Text = $"Could not enumerate display modes: {ex.Message}";
        }
        finally
        {
            _isRefreshing = false;
        }
        UpdateBoostUi();
    }

    private void UpdateTargetStatus()
    {
        if (_isRefreshing || _targetPicker.SelectedItem is not TargetPreset preset) return;
        if (preset.SupportedMode.HasValue)
        {
            var mode = preset.SupportedMode.Value;
            _availabilityLabel.ForeColor = Accent;
            _availabilityLabel.Text = $"AVAILABLE · {mode.dmBitsPerPel}-bit · {Math.Max(1, mode.dmDisplayFrequency)} Hz mode reported by Windows.";
            _applyButton.Enabled = true;
        }
        else
        {
            _availabilityLabel.ForeColor = Warning;
            _availabilityLabel.Text = "NOT AVAILABLE · This resolution is not exposed by the current GPU/display driver. Enable a driver-level virtual resolution, then refresh.";
            _applyButton.Enabled = false;
        }
    }

    private void UpdateBoostUi()
    {
        _boostStateLabel.Text = _boostEnabled
            ? "ON  ·  GLOBAL WINDOWS DESKTOP MODE"
            : "OFF  ·  DISPLAY UNCHANGED";
        _boostStateLabel.ForeColor = _boostEnabled ? Accent : Muted;
        _toggleButton.Text = _boostEnabled ? "DISABLE & RESTORE PREVIOUS MODE" : "ENABLE SCREEN BOOST";
        _toggleButton.BackColor = _boostEnabled ? CardAlt : Accent;
        _toggleButton.ForeColor = _boostEnabled ? Foreground : Color.FromArgb(17, 22, 15);
        UpdateTargetStatus();
    }

    private void ToggleBoost()
    {
        if (_boostEnabled)
        {
            var answer = MessageBox.Show(
                this,
                "Turn Screen Boost off and restore the display mode captured before the boost was enabled?",
                "Restore display mode",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return;
            RestoreBaseline();
        }
        else
        {
            ApplySelectedMode();
        }
    }

    private void ApplySelectedMode()
    {
        if (_targetPicker.SelectedItem is not TargetPreset preset) return;
        if (!preset.SupportedMode.HasValue)
        {
            MessageBox.Show(this,
                "Windows/GPU drivers do not currently expose this exact mode. Enable NVIDIA DSR or AMD Virtual Super Resolution in the graphics driver, then refresh supported modes.",
                "Resolution unavailable", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var before = GetCurrentMode();
        if (!before.HasValue)
        {
            MessageBox.Show(this, "Could not read the current Windows display mode. No changes were made.",
                "Display mode unavailable", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        var target = preset.SupportedMode.Value;
        target.dmFields = DmBitsPerPel | DmPelsWidth | DmPelsHeight | DmDisplayFrequency;
        var test = target;
        var testResult = ChangeDisplaySettings(ref test, CdsTest);
        if (testResult != DisplayChangeSuccessful)
        {
            _statusLabel.Text = $"Windows rejected the mode during its safety test (code {testResult}).";
            MessageBox.Show(this,
                $"Windows/GPU drivers rejected {preset.Width:N0} × {preset.Height:N0} (code {testResult}). The current display was left unchanged.",
                "Mode test failed", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        if (before.Value.dmPelsWidth == preset.Width && before.Value.dmPelsHeight == preset.Height)
        {
            if (!_boostEnabled) _baselineMode = before;
            _boostEnabled = true;
            _statusLabel.Text = "Selected mode is already active. Screen Boost is now marked on.";
            UpdateBoostUi();
            return;
        }

        var applyResult = ChangeDisplaySettings(ref target, CdsFullscreen);
        if (applyResult != DisplayChangeSuccessful)
        {
            _statusLabel.Text = $"Windows failed to apply the display mode (code {applyResult}).";
            MessageBox.Show(this,
                $"Could not apply the selected display mode (Windows code {applyResult}). No permanent display setting was saved.",
                "Unable to apply resolution", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            RefreshDisplayModes();
            return;
        }

        using var confirmation = new DisplayChangeConfirmationForm(preset.Label, preset.Width, preset.Height);
        var keep = confirmation.ShowDialog(this) == DialogResult.OK;
        if (!keep)
        {
            var restore = before.Value;
            ChangeDisplaySettings(ref restore, CdsFullscreen);
            _statusLabel.Text = "Display change reverted. The previous resolution was restored.";
            RefreshDisplayModes();
            return;
        }

        if (!_boostEnabled) _baselineMode = before;
        _boostEnabled = true;
        _statusLabel.Text = $"Screen Boost on · desktop now uses {preset.Width:N0} × {preset.Height:N0}.";
        RefreshDisplayModes();
        ShowPanel();
    }

    private void RestoreBaseline()
    {
        if (_baselineMode.HasValue)
        {
            var baseline = _baselineMode.Value;
            var result = ChangeDisplaySettings(ref baseline, CdsFullscreen);
            if (result != DisplayChangeSuccessful)
            {
                MessageBox.Show(this, $"Windows could not restore the saved mode (code {result}). Use Windows Display Settings to restore it manually.",
                    "Restore failed", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
        }
        _baselineMode = null;
        _boostEnabled = false;
        _statusLabel.Text = "Screen Boost off · previous display mode restored.";
        RefreshDisplayModes();
    }

    private void OpenDisplaySettings()
    {
        try
        {
            Process.Start(new ProcessStartInfo("ms-settings:display") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Could not open Windows Display Settings: {ex.Message}", "Open settings failed",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void OpenNvidiaPanel()
    {
        try
        {
            Process.Start(new ProcessStartInfo("nvcplui.exe") { UseShellExecute = true });
        }
        catch
        {
            MessageBox.Show(this,
                "NVIDIA Control Panel was not found. If you have an NVIDIA GPU, install/open NVIDIA Control Panel from Windows, enable Dynamic Super Resolution (DSR) factors, then return here and click Refresh Modes. AMD cards may offer Virtual Super Resolution (VSR).",
                "Driver panel not found", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void ShowPanel()
    {
        if (IsDisposed) return;
        if (!Visible) Show();
        WindowState = FormWindowState.Normal;
        PositionNearTaskbar();
        Activate();
    }

    private void ExitAndRestore()
    {
        if (_boostEnabled) RestoreBaseline();
        _allowExit = true;
        _trayIcon.Visible = false;
        Close();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _trayIcon.Dispose();
            _trayMenu.Dispose();
        }
        base.Dispose(disposing);
    }
}

internal sealed class DisplayChangeConfirmationForm : Form
{
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 250 };
    private readonly DateTime _deadline = DateTime.UtcNow.AddSeconds(15);
    private readonly Label _countdown = new();

    public DisplayChangeConfirmationForm(string name, int width, int height)
    {
        Text = "Confirm display mode";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        ShowInTaskbar = false;
        TopMost = true;
        MaximizeBox = false;
        MinimizeBox = false;
        ControlBox = false;
        Size = new Size(420, 215);
        BackColor = Color.FromArgb(20, 23, 29);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9);

        var title = new Label
        {
            Text = $"Keep {name} ({width:N0} × {height:N0})?",
            Font = new Font("Segoe UI Semibold", 12, FontStyle.Bold),
            ForeColor = Color.White,
            Location = new Point(18, 18),
            Size = new Size(370, 30)
        };
        var message = new Label
        {
            Text = "Windows will automatically restore your previous resolution in 15 seconds unless you confirm the new display mode.",
            ForeColor = Color.FromArgb(173, 181, 194),
            Location = new Point(18, 56),
            Size = new Size(370, 48)
        };
        _countdown.Location = new Point(18, 108);
        _countdown.Size = new Size(370, 24);
        _countdown.ForeColor = Color.FromArgb(166, 255, 80);
        _countdown.Font = new Font("Segoe UI", 9, FontStyle.Bold);

        var keep = new Button
        {
            Text = "KEEP MODE",
            Location = new Point(18, 143),
            Size = new Size(175, 34),
            DialogResult = DialogResult.OK,
            BackColor = Color.FromArgb(166, 255, 80),
            ForeColor = Color.FromArgb(17, 22, 15),
            FlatStyle = FlatStyle.Flat
        };
        keep.FlatAppearance.BorderSize = 0;
        var revert = new Button
        {
            Text = "REVERT NOW",
            Location = new Point(205, 143),
            Size = new Size(183, 34),
            DialogResult = DialogResult.Cancel,
            BackColor = Color.FromArgb(49, 54, 64),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };
        revert.FlatAppearance.BorderSize = 0;
        AcceptButton = keep;
        CancelButton = revert;
        Controls.AddRange(new Control[] { title, message, _countdown, keep, revert });

        _timer.Tick += (_, _) =>
        {
            var remaining = _deadline - DateTime.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                DialogResult = DialogResult.Cancel;
                Close();
                return;
            }
            _countdown.Text = $"Automatic revert in {Math.Ceiling(remaining.TotalSeconds)} seconds";
        };
        Shown += (_, _) => _timer.Start();
        FormClosed += (_, _) => _timer.Stop();
        _countdown.Text = "Automatic revert in 15 seconds";
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _timer.Dispose();
        base.Dispose(disposing);
    }
}
