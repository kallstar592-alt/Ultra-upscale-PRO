package com.ultraupscaler.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight multi-tab Android browser shell for Ultra-upscale-PRO.
 * The selected 16K/32K values are experimental rendering targets, not guarantees
 * that a website supplies or plays native 16K/32K media.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "ultra_upscaler_settings";
    private static final String HOME_URL = "https://duckduckgo.com";
    private static final int BG = Color.rgb(17, 17, 27);
    private static final int PANEL = Color.rgb(31, 31, 46);
    private static final int ACCENT = Color.rgb(139, 92, 246);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(176, 177, 195);

    private final ArrayList<BrowserTab> tabs = new ArrayList<>();
    private int nextTabId = 1;
    private BrowserTab activeTab;
    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout tabStrip;
    private FrameLayout webContainer;
    private EditText addressBar;
    private TextView statusView;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private String enhanceScript = "";
    private String adBlockScript = "";

    private static final class BrowserTab {
        final int id;
        WebView webView;
        String title = "New Tab";
        String url = "about:blank";
        BrowserTab(int id) { this.id = id; }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        enhanceScript = readAsset("inject.js");
        adBlockScript = readAsset("adblock.js");
        buildBrowserUi();
        createTab(HOME_URL, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (BrowserTab tab : tabs) {
            if (tab.webView != null && tab != activeTab) tab.webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeTab != null && activeTab.webView != null) activeTab.webView.onResume();
    }

    @Override
    public void onBackPressed() {
        if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoBack()) {
            activeTab.webView.goBack();
        } else if (tabs.size() > 1 && activeTab != null) {
            closeTab(activeTab);
        } else {
            super.onBackPressed();
        }
    }

    private void buildBrowserUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsRow.setPadding(dp(5), dp(3), dp(5), dp(3));

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabsScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabsRow.addView(tabsScroll, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button addTab = makeButton("+", true);
        addTab.setContentDescription("Open new tab");
        addTab.setMinWidth(dp(42));
        tabsRow.addView(addTab, new LinearLayout.LayoutParams(dp(42), dp(38)));
        addTab.setOnClickListener(v -> createTab("about:blank", true));
        root.addView(tabsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(5), dp(3), dp(5), dp(4));
        navigation.setBackgroundColor(PANEL);

        backButton = makeButton("‹", false);
        backButton.setContentDescription("Back");
        forwardButton = makeButton("›", false);
        forwardButton.setContentDescription("Forward");
        reloadButton = makeButton("↻", false);
        reloadButton.setContentDescription("Reload");
        navigation.addView(backButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        navigation.addView(forwardButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        navigation.addView(reloadButton, new LinearLayout.LayoutParams(dp(38), dp(38)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(TEXT);
        addressBar.setHintTextColor(MUTED);
        addressBar.setHint("Search or enter website");
        addressBar.setTextSize(14);
        addressBar.setPadding(dp(10), 0, dp(10), 0);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setBackground(roundDrawable(Color.rgb(45, 45, 64), dp(12)));
        navigation.addView(addressBar, new LinearLayout.LayoutParams(0, dp(38), 1f));

        Button goButton = makeButton("Go", true);
        navigation.addView(goButton, new LinearLayout.LayoutParams(dp(48), dp(38)));
        Button enhanceButton = makeButton("Enhance", true);
        enhanceButton.setTextSize(11);
        navigation.addView(enhanceButton, new LinearLayout.LayoutParams(dp(76), dp(38)));
        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(47)));

        webContainer = new FrameLayout(this);
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(MUTED);
        statusView.setTextSize(11);
        statusView.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusView.setSingleLine(true);
        statusView.setText("Ready · Ad blocker " + (isAdBlockEnabled() ? "on" : "off"));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));

        backButton.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView.canGoBack()) activeTab.webView.goBack();
        });
        forwardButton.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView.canGoForward()) activeTab.webView.goForward();
        });
        reloadButton.setOnClickListener(v -> {
            if (activeTab != null) activeTab.webView.reload();
        });
        goButton.setOnClickListener(v -> navigateAddress());
        addressBar.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                navigateAddress();
                return true;
            }
            return false;
        });
        enhanceButton.setOnClickListener(v -> showEnhanceDialog());
        updateNavigationButtons();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView makeWebView(BrowserTab tab) {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new AndroidBridge(tab.id), "AndroidVQ");
        web.setWebViewClient(new BrowserClient(tab));
        web.setWebChromeClient(new BrowserChromeClient(tab));
        return web;
    }

    private void createTab(String url, boolean navigate) {
        BrowserTab tab = new BrowserTab(nextTabId++);
        tab.webView = makeWebView(tab);
        tabs.add(tab);
        selectTab(tab);
        if (navigate && url != null && !url.isEmpty()) tab.webView.loadUrl(url);
        renderTabs();
    }

    private void selectTab(BrowserTab tab) {
        if (tab == null || tab.webView == null) return;
        if (activeTab != null && activeTab.webView != null && activeTab != tab) {
            activeTab.webView.onPause();
        }
        activeTab = tab;
        if (tab.webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
        }
        webContainer.removeAllViews();
        webContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.webView.onResume();
        addressBar.setText(tab.url == null || "about:blank".equals(tab.url) ? "" : tab.url);
        updateNavigationButtons();
        renderTabs();
    }

    private void closeTab(BrowserTab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) return;
        boolean wasActive = activeTab == tab;
        tabs.remove(index);
        if (tab.webView != null) {
            if (tab.webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
            }
            tab.webView.stopLoading();
            tab.webView.removeJavascriptInterface("AndroidVQ");
            tab.webView.loadUrl("about:blank");
            tab.webView.destroy();
            tab.webView = null;
        }
        if (tabs.isEmpty()) {
            activeTab = null;
            createTab("about:blank", false);
        } else if (wasActive) {
            activeTab = null;
            selectTab(tabs.get(Math.max(0, Math.min(index - 1, tabs.size() - 1))));
        } else {
            renderTabs();
        }
    }

    private void renderTabs() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        for (BrowserTab tab : tabs) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(8), 0, dp(4), 0);
            chip.setBackground(roundDrawable(tab == activeTab ? ACCENT : PANEL, dp(10)));
            TextView title = new TextView(this);
            title.setText(tabTitle(tab));
            title.setTextColor(TEXT);
            title.setTextSize(12);
            title.setMaxLines(1);
            title.setMaxWidth(dp(130));
            title.setTypeface(null, tab == activeTab ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            chip.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
            Button close = makeButton("×", false);
            close.setMinWidth(dp(27));
            close.setTextSize(15);
            close.setContentDescription("Close tab " + tabTitle(tab));
            chip.addView(close, new LinearLayout.LayoutParams(dp(27), dp(31)));
            close.setOnClickListener(v -> closeTab(tab));
            chip.setOnClickListener(v -> selectTab(tab));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            params.setMargins(dp(2), 0, dp(2), 0);
            tabStrip.addView(chip, params);
        }
    }

    private String tabTitle(BrowserTab tab) {
        String title = tab.title == null ? "" : tab.title.trim();
        if (title.isEmpty()) title = "New Tab";
        return title.length() > 22 ? title.substring(0, 21) + "…" : title;
    }

    private void navigateAddress() {
        String text = addressBar.getText().toString().trim();
        if (text.isEmpty() || activeTab == null) return;
        String url;
        if (text.matches("(?i)^https?://.*")) {
            url = text;
        } else if (!text.contains(" ") && text.contains(".") && !text.startsWith("about:")) {
            url = "https://" + text;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(text);
        }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        activeTab.webView.loadUrl(url);
    }

    private void updateNavigationButtons() {
        boolean hasTab = activeTab != null && activeTab.webView != null;
        if (backButton != null) backButton.setEnabled(hasTab && activeTab.webView.canGoBack());
        if (forwardButton != null) forwardButton.setEnabled(hasTab && activeTab.webView.canGoForward());
        if (reloadButton != null) reloadButton.setEnabled(hasTab);
    }

    private final class BrowserClient extends WebViewClient {
        private final BrowserTab tab;
        BrowserClient(BrowserTab tab) { this.tab = tab; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return routeUrl(request.getUrl().toString(), request.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return routeUrl(url, true);
        }

        private boolean routeUrl(String url, boolean mainFrame) {
            if (url == null) return false;
            if (isAdBlockEnabled() && AdBlocker.isBlocked(url)) return true;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null || "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)
                    || "javascript".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme)) {
                if (mainFrame && tab == activeTab) addressBar.setText(url);
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(MainActivity.this, "No app can open that link", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (isAdBlockEnabled() && AdBlocker.isBlocked(request.getUrl().toString())) {
                return AdBlocker.emptyResponse();
            }
            return super.shouldInterceptRequest(view, request);
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (isAdBlockEnabled() && AdBlocker.isBlocked(url)) return AdBlocker.emptyResponse();
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            tab.url = url;
            if (tab == activeTab) {
                addressBar.setText(url);
                statusView.setText("Loading · " + url);
            }
            renderTabs();
            updateNavigationButtons();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            tab.url = url;
            if (tab == activeTab) addressBar.setText(url);
            injectSettings(tab);
            renderTabs();
            updateNavigationButtons();
        }
    }

    private final class BrowserChromeClient extends WebChromeClient {
        private final BrowserTab tab;
        BrowserChromeClient(BrowserTab tab) { this.tab = tab; }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (title != null && !title.trim().isEmpty()) tab.title = title.trim();
            renderTabs();
        }

        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (tab == activeTab && progress < 100) statusView.setText("Loading · " + progress + "%");
            updateNavigationButtons();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message resultMsg) {
            // Reject non-user initiated windows to reduce pop-up and ad abuse.
            if (!userGesture) return false;
            BrowserTab popup = new BrowserTab(nextTabId++);
            popup.webView = makeWebView(popup);
            tabs.add(popup);
            selectTab(popup);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup.webView);
            resultMsg.sendToTarget();
            renderTabs();
            return true;
        }
    }

    private final class AndroidBridge {
        private final int tabId;
        AndroidBridge(int tabId) { this.tabId = tabId; }

        @JavascriptInterface
        public void status(String value) {
            runOnUiThread(() -> {
                if (activeTab != null && activeTab.id == tabId && value != null) {
                    statusView.setText(value);
                }
            });
        }
    }

    private void injectSettings(BrowserTab tab) {
        if (tab == null || tab.webView == null) return;
        try {
            JSONObject config = new JSONObject();
            config.put("enabled", prefs.getBoolean("enhanceEnabled", true));
            config.put("target", prefs.getString("target", "4k"));
            config.put("sharpness", prefs.getInt("sharpness", 35));
            String configText = config.toString();
            String js = "(function(){try{"
                    + "if(!window.__uaEnhanceLoaded){var s=document.createElement('script');"
                    + "s.textContent=" + JSONObject.quote(enhanceScript) + ";"
                    + "(document.head||document.documentElement).appendChild(s);window.__uaEnhanceLoaded=true;}"
                    + "if(window.__ultraApplyConfig)window.__ultraApplyConfig(" + configText + ");"
                    + (isAdBlockEnabled()
                    ? "if(!window.__uaAdScriptLoaded){var a=document.createElement('script');a.textContent="
                        + JSONObject.quote(adBlockScript)
                        + ";(document.head||document.documentElement).appendChild(a);window.__uaAdScriptLoaded=true;}"
                    : "")
                    + "}catch(e){}})();";
            tab.webView.evaluateJavascript(js, null);
        } catch (Exception ignored) { }
    }

    private boolean isAdBlockEnabled() {
        return prefs == null || prefs.getBoolean("adBlockEnabled", true);
    }

    private void showEnhanceDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(12));
        panel.setBackgroundColor(BG);
        scroll.addView(panel);

        TextView intro = sectionLabel("VIDEO ENHANCEMENT");
        intro.setText("Enhance video rendering and manage browser protection.");
        panel.addView(intro);

        Switch enable = new Switch(this);
        enable.setText("Enable video enhancement");
        enable.setTextColor(TEXT);
        enable.setChecked(prefs.getBoolean("enhanceEnabled", true));
        addControl(panel, enable);

        panel.addView(sectionLabel("Enhancement target"));
        String[] targets = {"4K", "8K UHD", "16K target (experimental)", "32K target (experimental)"};
        Spinner targetSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, targets);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
        targetSpinner.setSelection(targetIndex(prefs.getString("target", "4k")));
        panel.addView(targetSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView sharpLabel = sectionLabel("Sharpness: " + prefs.getInt("sharpness", 35) + "%");
        panel.addView(sharpLabel);
        SeekBar sharpness = new SeekBar(this);
        sharpness.setMax(100);
        sharpness.setProgress(prefs.getInt("sharpness", 35));
        sharpness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                sharpLabel.setText("Sharpness: " + value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        panel.addView(sharpness, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        CheckBox adBlock = new CheckBox(this);
        adBlock.setText("Enable ad and tracker blocker");
        adBlock.setTextColor(TEXT);
        adBlock.setChecked(isAdBlockEnabled());
        addControl(panel, adBlock);

        TextView note = new TextView(this);
        note.setText("16K/32K are best-effort render targets, not native-source playback guarantees. GPU, memory and video-site limits apply. Ad blocking uses a small built-in list and common cosmetic rules; some ads may remain.");
        note.setTextColor(MUTED);
        note.setTextSize(12);
        note.setPadding(dp(4), dp(6), dp(4), dp(6));
        panel.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Enhance")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    boolean wasBlocking = isAdBlockEnabled();
                    String[] keys = {"4k", "8k", "16k", "32k"};
                    int targetIndex = Math.max(0, Math.min(keys.length - 1,
                            targetSpinner.getSelectedItemPosition()));
                    prefs.edit()
                            .putBoolean("enhanceEnabled", enable.isChecked())
                            .putString("target", keys[targetIndex])
                            .putInt("sharpness", sharpness.getProgress())
                            .putBoolean("adBlockEnabled", adBlock.isChecked())
                            .apply();
                    dialog.dismiss();
                    for (BrowserTab tab : new ArrayList<>(tabs)) {
                        if (tab.webView == null) continue;
                        if (wasBlocking != isAdBlockEnabled() && tab.url != null
                                && !tab.url.equals("about:blank")) {
                            tab.webView.reload();
                        } else {
                            injectSettings(tab);
                        }
                    }
                    statusView.setText("Settings applied · Ad blocker "
                            + (isAdBlockEnabled() ? "on" : "off"));
                }));
        dialog.show();
    }

    private int targetIndex(String target) {
        if ("8k".equals(target)) return 1;
        if ("16k".equals(target)) return 2;
        if ("32k".equals(target)) return 3;
        return 0;
    }

    private void addControl(LinearLayout panel, View control) {
        panel.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
    }

    private TextView sectionLabel(String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(TEXT);
        label.setTextSize(13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(dp(3), dp(8), dp(3), dp(4));
        return label;
    }

    private Button makeButton(String value, boolean highlighted) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(roundDrawable(highlighted ? ACCENT : PANEL, dp(10)));
        return button;
    }

    private GradientDrawable roundDrawable(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buffer = new byte[4096];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = in.read(buffer)) > 0) out.append(new String(buffer, 0, n, "UTF-8"));
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static final class AdBlocker {
        private static final Set<String> DOMAINS = new HashSet<>(Arrays.asList(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "adservice.google.com", "adnxs.com", "adsrvr.org", "taboola.com",
                "outbrain.com", "criteo.com", "scorecardresearch.com", "moatads.com",
                "pubmatic.com", "openx.net", "rubiconproject.com", "adform.net",
                "serving-sys.com", "amazon-adsystem.com", "casalemedia.com",
                "smartadserver.com", "3lift.com", "yieldmo.com", "media.net", "mgid.com"));

        static boolean isBlocked(String value) {
            try {
                Uri uri = Uri.parse(value);
                String host = uri.getHost();
                if (host == null) return false;
                host = host.toLowerCase(Locale.ROOT);
                for (String domain : DOMAINS) {
                    if (host.equals(domain) || host.endsWith("." + domain)) return true;
                }
                String path = uri.getPath();
                return path != null && (path.contains("/pagead/") || path.contains("/adservice/")
                        || path.contains("/adsystem/"));
            } catch (Exception e) {
                return false;
            }
        }

        static WebResourceResponse emptyResponse() {
            return new WebResourceResponse("text/plain", "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        }
    }
}