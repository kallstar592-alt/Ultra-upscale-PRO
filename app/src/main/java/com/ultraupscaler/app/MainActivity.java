package com.ultraupscaler.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reconstructed, editable Android WebView browser shell for the supplied compiled APK.
 * Browser tabs are independent WebViews; enhancement is a rendering target, not a source-stream guarantee.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "ultra_upscaler_settings";
    private static final String HOME_URL = "https://duckduckgo.com";
    private static final int BG = Color.rgb(17, 17, 27);
    private static final int PANEL = Color.rgb(31, 31, 46);
    private static final int ACCENT = Color.rgb(139, 92, 246);
    private static final int TEXT = Color.rgb(245, 245, 250);
    private static final int MUTED = Color.rgb(176, 177, 195);

    private LinearLayout root;
    private LinearLayout tabStrip;
    private ScrollView controlDropdown;
    private Button eyeButton;
    private FrameLayout webContainer;
    private EditText addressBar;
    private TextView statusView;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private final ArrayList<BrowserTab> tabs = new ArrayList<>();
    private int nextTabId = 1;
    private BrowserTab activeTab;
    private SharedPreferences prefs;
    private String enhancementScript = "";
    private String aiUpscalerScript = "";
    private String adBlockScript = "";
    private View customVideoView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int oldSystemUiVisibility;

    private static final class BrowserTab {
        final int id;
        WebView webView;
        String title = "New Tab";
        String url = "about:blank";
        BrowserTab(int id) { this.id = id; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        enhancementScript = readAsset("inject.js");
        aiUpscalerScript = readAsset("ai-upscaler.js");
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

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customVideoView != null) {
            hideCustomView();
            return;
        }
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

        // The tab strip stays visible. Navigation and all enhancement settings are tucked under the eye menu.
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsRow.setPadding(dp(5), dp(3), dp(5), dp(3));
        tabsRow.setBackgroundColor(BG);

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabsScroll.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabsRow.addView(tabsScroll, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button addTabButton = makeButton("+", true);
        addTabButton.setTextSize(18);
        addTabButton.setContentDescription("Open a new DuckDuckGo tab");
        addTabButton.setMinWidth(dp(38));
        tabsRow.addView(addTabButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        addTabButton.setOnClickListener(v -> createTab(HOME_URL, true));

        eyeButton = makeButton("👁", false);
        eyeButton.setTextSize(16);
        eyeButton.setContentDescription("Show browser controls");
        tabsRow.addView(eyeButton, new LinearLayout.LayoutParams(dp(42), dp(38)));
        root.addView(tabsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));

        buildControlDropdown();

        webContainer = new FrameLayout(this);
        webContainer.setBackgroundColor(Color.WHITE);
        root.addView(webContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        eyeButton.setOnClickListener(v -> toggleControlDropdown());
    }

    private void toggleControlDropdown() {
        if (controlDropdown == null || eyeButton == null) return;
        boolean show = controlDropdown.getVisibility() != View.VISIBLE;
        controlDropdown.setVisibility(show ? View.VISIBLE : View.GONE);
        eyeButton.setContentDescription(show ? "Hide browser controls" : "Show browser controls");
        eyeButton.setBackground(roundDrawable(show ? ACCENT : PANEL, dp(10)));
        if (show) updateNavButtons();
    }

    private void buildControlDropdown() {
        controlDropdown = new ScrollView(this);
        controlDropdown.setFillViewport(false);
        controlDropdown.setBackgroundColor(BG);
        controlDropdown.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(9), dp(7), dp(9), dp(9));
        panel.setBackgroundColor(BG);
        controlDropdown.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(0, 0, 0, dp(6));

        backButton = makeButton("‹", false);
        backButton.setContentDescription("Back");
        forwardButton = makeButton("›", false);
        forwardButton.setContentDescription("Forward");
        reloadButton = makeButton("↻", false);
        reloadButton.setContentDescription("Reload page");
        navRow.addView(backButton, new LinearLayout.LayoutParams(dp(32), dp(38)));
        navRow.addView(forwardButton, new LinearLayout.LayoutParams(dp(32), dp(38)));
        navRow.addView(reloadButton, new LinearLayout.LayoutParams(dp(32), dp(38)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(TEXT);
        addressBar.setHintTextColor(MUTED);
        addressBar.setHint("Search DuckDuckGo or enter website");
        addressBar.setTextSize(14);
        addressBar.setPadding(dp(9), 0, dp(9), 0);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setBackground(roundDrawable(Color.rgb(45, 45, 64), dp(12)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        addressParams.setMargins(dp(4), 0, dp(4), 0);
        navRow.addView(addressBar, addressParams);

        Button goButton = makeButton("Go", true);
        navRow.addView(goButton, new LinearLayout.LayoutParams(dp(42), dp(38)));
        panel.addView(navRow);

        backButton.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoBack()) activeTab.webView.goBack();
        });
        forwardButton.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null && activeTab.webView.canGoForward()) activeTab.webView.goForward();
        });
        reloadButton.setOnClickListener(v -> {
            if (activeTab != null && activeTab.webView != null) activeTab.webView.reload();
        });
        goButton.setOnClickListener(v -> navigateFromAddressBar());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateFromAddressBar();
                return true;
            }
            return false;
        });

        Switch enhanceSwitch = new Switch(this);
        enhanceSwitch.setText("Enable video enhancement");
        enhanceSwitch.setTextColor(TEXT);
        enhanceSwitch.setChecked(prefs.getBoolean("enhanceEnabled", true));
        addControl(panel, enhanceSwitch);

        Switch aiUpscalerSwitch = new Switch(this);
        aiUpscalerSwitch.setText("AI Upscaler · experimental");
        aiUpscalerSwitch.setTextColor(TEXT);
        aiUpscalerSwitch.setChecked(prefs.getBoolean("aiUpscaler", false));
        addControl(panel, aiUpscalerSwitch);

        TextView aiInfo = new TextView(this);
        aiInfo.setText("AI mode needs internet for the first model download. Speed and video-site restrictions vary by device and site.");
        aiInfo.setTextColor(MUTED);
        aiInfo.setTextSize(11);
        aiInfo.setPadding(dp(3), 0, dp(3), dp(4));
        panel.addView(aiInfo);

        panel.addView(sectionLabel("Resolution target"));
        Spinner targetSpinner = new Spinner(this);
        String[] targets = {"4K", "5K", "8K UHD", "10K", "16K target", "32K target"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, targets) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setBackgroundColor(BG);
                view.setPadding(dp(10), dp(3), dp(10), dp(3));
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setBackgroundColor(PANEL);
                view.setPadding(dp(12), dp(9), dp(12), dp(9));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
        targetSpinner.setSelection(targetIndex(prefs.getString("target", "4k")));
        panel.addView(targetSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        CheckBox smart = new CheckBox(this);
        smart.setText("Smart edge enhancement");
        smart.setTextColor(TEXT);
        smart.setChecked(prefs.getBoolean("smart", false));
        addControl(panel, smart);

        CheckBox autoQuality = new CheckBox(this);
        autoQuality.setText("Prefer highest available YouTube quality");
        autoQuality.setTextColor(TEXT);
        autoQuality.setChecked(prefs.getBoolean("autoQuality", true));
        addControl(panel, autoQuality);

        CheckBox adaptive = new CheckBox(this);
        adaptive.setText("Reduce render load if frames drop");
        adaptive.setTextColor(TEXT);
        adaptive.setChecked(prefs.getBoolean("adaptive", true));
        addControl(panel, adaptive);

        SeekBar sharp = addRangeSlider(panel, "Sharpness", 0, 100, prefs.getInt("sharpness", 35));
        SeekBar brightness = addRangeSlider(panel, "Brightness", 50, 150, prefs.getInt("brightness", 100));
        SeekBar contrast = addRangeSlider(panel, "Contrast", 50, 150, prefs.getInt("contrast", 100));
        SeekBar saturation = addRangeSlider(panel, "Saturation", 0, 200, prefs.getInt("saturation", 100));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(61, 61, 80));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(6), 0, dp(6));
        panel.addView(divider, dividerParams);

        CheckBox adBlock = new CheckBox(this);
        adBlock.setText("Enable ad and tracker blocker");
        adBlock.setTextColor(TEXT);
        adBlock.setChecked(isAdBlockEnabled());
        addControl(panel, adBlock);

        statusView = new TextView(this);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setTextSize(11);
        statusView.setTextColor(MUTED);
        statusView.setPadding(dp(4), dp(5), dp(4), dp(5));
        statusView.setText("Ready · DuckDuckGo · Ads blocked: " + (isAdBlockEnabled() ? "on" : "off"));
        panel.addView(statusView);

        Button applyButton = makeButton("Apply settings", true);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        applyParams.topMargin = dp(5);
        panel.addView(applyButton, applyParams);
        applyButton.setOnClickListener(v -> {
            boolean previousAdBlock = isAdBlockEnabled();
            String target = targetForIndex(targetSpinner.getSelectedItemPosition());
            prefs.edit()
                    .putBoolean("enhanceEnabled", enhanceSwitch.isChecked())
                    .putBoolean("aiUpscaler", aiUpscalerSwitch.isChecked())
                    .putString("target", target)
                    .putBoolean("smart", smart.isChecked())
                    .putBoolean("autoQuality", autoQuality.isChecked())
                    .putBoolean("adaptive", adaptive.isChecked())
                    .putInt("sharpness", sharp.getProgress())
                    .putInt("brightness", brightness.getProgress() + 50)
                    .putInt("contrast", contrast.getProgress() + 50)
                    .putInt("saturation", saturation.getProgress())
                    .putBoolean("adBlockEnabled", adBlock.isChecked())
                    .apply();
            if (previousAdBlock != isAdBlockEnabled()) {
                for (BrowserTab tab : new ArrayList<>(tabs)) {
                    if (tab.webView != null && tab.url != null && !"about:blank".equals(tab.url)) tab.webView.reload();
                    else if (tab.webView != null) injectEnhancement(tab);
                }
            } else {
                for (BrowserTab tab : tabs) injectEnhancement(tab);
            }
            statusView.setText("Applied · DuckDuckGo · Ads/tracker blocking " + (isAdBlockEnabled() ? "on" : "off"));
        });

        root.addView(controlDropdown, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
    }

        private Button makeButton(String label, boolean prominent) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setTextColor(TEXT);
        button.setBackground(roundDrawable(prominent ? ACCENT : PANEL, dp(10)));
        return button;
    }

    private GradientDrawable roundDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createTab(String url, boolean navigate) {
        BrowserTab tab = new BrowserTab(nextTabId++);
        tab.webView = makeWebView(tab);
        tabs.add(tab);
        selectTab(tab);
        if (navigate && url != null && !"about:blank".equals(url)) {
            tab.webView.loadUrl(url);
        } else {
            addressBar.requestFocus();
            addressBar.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private WebView makeWebView(BrowserTab tab) {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        web.addJavascriptInterface(new AndroidBridge(tab.id), "AndroidVQ");
        web.setWebViewClient(new BrowserClient(tab));
        web.setWebChromeClient(new BrowserChromeClient(tab));
        return web;
    }

    private void selectTab(BrowserTab tab) {
        if (tab == null || tab.webView == null) return;
        if (activeTab != null && activeTab.webView != null && activeTab != tab) {
            activeTab.webView.onPause();
        }
        activeTab = tab;
        webContainer.removeAllViews();
        if (tab.webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
        }
        webContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.webView.onResume();
        addressBar.setText(tab.url == null || "about:blank".equals(tab.url) ? "" : tab.url);
        updateNavButtons();
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
            tab.webView.loadUrl("about:blank");
            tab.webView.removeJavascriptInterface("AndroidVQ");
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
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setPadding(dp(6), 0, dp(3), 0);
            chip.setBackground(roundDrawable(tab == activeTab ? ACCENT : PANEL, dp(10)));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(dp(142), dp(36));
            chipParams.setMargins(dp(2), dp(2), dp(3), dp(2));
            TextView title = new TextView(this);
            title.setText(tabTitle(tab));
            title.setTextColor(TEXT);
            title.setTextSize(11);
            title.setTypeface(null, tab == activeTab ? Typeface.BOLD : Typeface.NORMAL);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER_VERTICAL);
            chip.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            title.setOnClickListener(v -> selectTab(tab));
            Button close = new Button(this);
            close.setText("×");
            close.setTextSize(16);
            close.setTextColor(TEXT);
            close.setAllCaps(false);
            close.setMinWidth(0);
            close.setMinimumWidth(0);
            close.setPadding(0, 0, 0, 0);
            close.setBackgroundColor(Color.TRANSPARENT);
            chip.addView(close, new LinearLayout.LayoutParams(dp(28), dp(32)));
            close.setContentDescription("Close tab " + tabTitle(tab));
            close.setOnClickListener(v -> closeTab(tab));
            chip.setOnClickListener(v -> selectTab(tab));
            tabStrip.addView(chip, chipParams);
        }
    }

    private String tabTitle(BrowserTab tab) {
        String title = tab.title == null || tab.title.trim().isEmpty() ? "New Tab" : tab.title.trim();
        return title;
    }

    private void navigateFromAddressBar() {
        if (activeTab == null) return;
        String text = addressBar.getText().toString().trim();
        if (text.isEmpty()) return;
        String url = resolveInput(text);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        addressBar.clearFocus();
        activeTab.url = url;
        activeTab.webView.loadUrl(url);
        updateNavButtons();
    }

    private String resolveInput(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) {
            if (lower.startsWith("https://") || lower.startsWith("http://")) return input;
            return input;
        }
        if (!input.contains(" ") && (input.contains(".") || input.equalsIgnoreCase("localhost"))) {
            return "https://" + input;
        }
        return "https://duckduckgo.com/?q=" + Uri.encode(input);
    }

    private void updateNavButtons() {
        if (activeTab == null || activeTab.webView == null) return;
        backButton.setAlpha(activeTab.webView.canGoBack() ? 1f : 0.45f);
        forwardButton.setAlpha(activeTab.webView.canGoForward() ? 1f : 0.45f);
    }

    private final class BrowserClient extends WebViewClient {
        private final BrowserTab tab;
        BrowserClient(BrowserTab tab) { this.tab = tab; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigationRequest(request.getUrl().toString(), request.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigationRequest(url, true);
        }

        private boolean handleNavigationRequest(String value, boolean mainFrame) {
            if (value == null) return false;
            if (isAdBlockEnabled() && AdBlocker.isBlocked(value)) return true;
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if (scheme == null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || "about".equalsIgnoreCase(scheme) || "javascript".equalsIgnoreCase(scheme)) {
                if (mainFrame && tab == activeTab) addressBar.setText(value);
                return false;
            }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
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
                updateNavButtons();
            }
            renderTabs();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            tab.url = url;
            if (tab == activeTab) addressBar.setText(url);
            injectEnhancement(tab);
            updateNavButtons();
            renderTabs();
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
        public void onProgressChanged(WebView view, int newProgress) {
            if (tab == activeTab && newProgress < 100) statusView.setText("Loading · " + newProgress + "%");
            updateNavButtons();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // Only open user-initiated popups as tabs; this also cuts down on ad-created tabs.
            if (!isUserGesture) return false;
            BrowserTab popup = new BrowserTab(nextTabId++);
            popup.webView = makeWebView(popup);
            tabs.add(popup);
            selectTab(popup);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup.webView);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customVideoView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customVideoView = view;
            customViewCallback = callback;
            oldSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            root.setVisibility(View.GONE);
            View decor = getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                ((ViewGroup) decor).addView(view, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        }

        @Override
        public void onHideCustomView() { hideCustomView(); }
    }

    private void hideCustomView() {
        if (customVideoView == null) return;
        ViewGroup parent = (ViewGroup) customVideoView.getParent();
        if (parent != null) parent.removeView(customVideoView);
        customVideoView = null;
        root.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(oldSystemUiVisibility);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private final class AndroidBridge {
        private final int tabId;
        AndroidBridge(int tabId) { this.tabId = tabId; }
        @JavascriptInterface
        public void status(String message) {
            runOnUiThread(() -> {
                if (activeTab != null && activeTab.id == tabId && message != null && !message.isEmpty()) {
                    statusView.setText(message);
                }
            });
        }
    }

    private void injectEnhancement(BrowserTab tab) {
        if (tab == null || tab.webView == null) return;
        StringBuilder js = new StringBuilder();
        if (isAdBlockEnabled() && !adBlockScript.isEmpty()) js.append(adBlockScript).append('\n');
        if (!enhancementScript.isEmpty()) js.append(enhancementScript).append('\n');
        js.append("if (window.__vqSet) window.__vqSet(").append(settingsJson()).append(");");
        try {
            JSONObject aiConfig = new JSONObject();
            aiConfig.put("enabled", prefs.getBoolean("aiUpscaler", false));
            aiConfig.put("target", prefs.getString("target", "4k").toUpperCase(Locale.ROOT));
            js.append("\nwindow.__ULTRA_AI_CONFIG = ").append(aiConfig.toString()).append(";\n");
        } catch (Exception ignored) { }
        if (!aiUpscalerScript.isEmpty()) js.append(aiUpscalerScript).append('\n');
        tab.webView.evaluateJavascript(js.toString(), null);
    }

    private String settingsJson() {
        JSONObject json = new JSONObject();
        try {
            // Avoid stacking the legacy WebGL overlay beneath the AI-generated video overlay.
            json.put("enabled", prefs.getBoolean("enhanceEnabled", true)
                    && !prefs.getBoolean("aiUpscaler", false));
            json.put("target", prefs.getString("target", "4k"));
            json.put("sharpness", prefs.getInt("sharpness", 35));
            json.put("brightness", prefs.getInt("brightness", 100));
            json.put("contrast", prefs.getInt("contrast", 100));
            json.put("saturation", prefs.getInt("saturation", 100));
            json.put("autoQuality", prefs.getBoolean("autoQuality", true));
            json.put("adaptive", prefs.getBoolean("adaptive", true));
            json.put("smart", prefs.getBoolean("smart", false));
            json.put("smartStrength", prefs.getInt("smartStrength", 60));
        } catch (Exception ignored) { }
        return json.toString();
    }

    private boolean isAdBlockEnabled() { return prefs.getBoolean("adBlockEnabled", true); }

    private void showEnhanceDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(12));
        panel.setBackgroundColor(BG);
        scroll.addView(panel);

        TextView intro = new TextView(this);
        intro.setText("Tune video rendering and browser protection.");
        intro.setTextColor(TEXT);
        intro.setTextSize(14);
        intro.setPadding(0, 0, 0, dp(8));
        panel.addView(intro);

        Switch enhanceSwitch = new Switch(this);
        enhanceSwitch.setText("Enable video enhancement");
        enhanceSwitch.setTextColor(TEXT);
        enhanceSwitch.setChecked(prefs.getBoolean("enhanceEnabled", true));
        addControl(panel, enhanceSwitch);

        Switch aiUpscalerSwitch = new Switch(this);
        aiUpscalerSwitch.setText("AI Upscaler · experimental x4 model");
        aiUpscalerSwitch.setTextColor(TEXT);
        aiUpscalerSwitch.setChecked(prefs.getBoolean("aiUpscaler", false));
        addControl(panel, aiUpscalerSwitch);

        TextView aiInfo = new TextView(this);
        aiInfo.setText("AI mode uses an experimental x4 super-resolution model. First use downloads the model/runtime. It can be slow, and protected or cross-origin video may block frame access. The standard renderer is paused while AI mode is on to prevent overlapping video layers.");
        aiInfo.setTextColor(MUTED);
        aiInfo.setTextSize(12);
        aiInfo.setPadding(dp(4), 0, dp(4), dp(8));
        panel.addView(aiInfo);

        TextView targetLabel = sectionLabel("Enhancement target");
        panel.addView(targetLabel);
        Spinner targetSpinner = new Spinner(this);
        String[] targets = {"4K", "5K", "8K UHD", "10K", "16K target (GPU-limited)", "32K target (GPU-limited)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, targets) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setBackgroundColor(BG);
                view.setPadding(dp(10), dp(4), dp(10), dp(4));
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(TEXT);
                view.setBackgroundColor(PANEL);
                view.setPadding(dp(12), dp(10), dp(12), dp(10));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
        String currentTarget = prefs.getString("target", "4k");
        targetSpinner.setSelection(targetIndex(currentTarget));
        panel.addView(targetSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        CheckBox smart = new CheckBox(this);
        smart.setText("Smart edge enhancement (experimental)");
        smart.setTextColor(TEXT);
        smart.setChecked(prefs.getBoolean("smart", false));
        addControl(panel, smart);

        CheckBox autoQuality = new CheckBox(this);
        autoQuality.setText("Prefer highest available YouTube source quality");
        autoQuality.setTextColor(TEXT);
        autoQuality.setChecked(prefs.getBoolean("autoQuality", true));
        addControl(panel, autoQuality);

        CheckBox adaptive = new CheckBox(this);
        adaptive.setText("Automatically lower render load if frames drop");
        adaptive.setTextColor(TEXT);
        adaptive.setChecked(prefs.getBoolean("adaptive", true));
        addControl(panel, adaptive);

        SeekBar sharp = addRangeSlider(panel, "Sharpness", 0, 100, prefs.getInt("sharpness", 35));
        SeekBar brightness = addRangeSlider(panel, "Brightness", 50, 150, prefs.getInt("brightness", 100));
        SeekBar contrast = addRangeSlider(panel, "Contrast", 50, 150, prefs.getInt("contrast", 100));
        SeekBar saturation = addRangeSlider(panel, "Saturation", 0, 200, prefs.getInt("saturation", 100));

                View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(61, 61, 80));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(12), 0, dp(10));
        panel.addView(divider, dividerParams);

        CheckBox adBlock = new CheckBox(this);
        adBlock.setText("Enable ad and tracker blocker");
        adBlock.setTextColor(TEXT);
        adBlock.setChecked(isAdBlockEnabled());
        addControl(panel, adBlock);

        TextView adInfo = new TextView(this);
        adInfo.setText("Uses a built-in domain blocklist plus basic page-element hiding. It is not a full EasyList replacement, so some ads may remain.");
        adInfo.setTextColor(MUTED);
        adInfo.setTextSize(12);
        adInfo.setPadding(dp(4), 0, dp(4), dp(8));
        panel.addView(adInfo);

        TextView limitation = new TextView(this);
        limitation.setText("Important: 16K and 32K are experimental upscale targets. The app does not make a website's source stream native 16K/32K or invent missing detail. GPU, memory, WebView and source-video limits can cap the actual render size; the status line reports the rendered dimensions.");
        limitation.setTextColor(MUTED);
        limitation.setTextSize(12);
        limitation.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.addView(limitation);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Enhance")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean previousAdBlock = isAdBlockEnabled();
            String target = targetForIndex(targetSpinner.getSelectedItemPosition());
            prefs.edit()
                    .putBoolean("enhanceEnabled", enhanceSwitch.isChecked())
                    .putBoolean("aiUpscaler", aiUpscalerSwitch.isChecked())
                    .putString("target", target)
                    .putBoolean("smart", smart.isChecked())
                    .putBoolean("autoQuality", autoQuality.isChecked())
                    .putBoolean("adaptive", adaptive.isChecked())
                    .putInt("sharpness", sharp.getProgress())
                    .putInt("brightness", brightness.getProgress() + 50)
                    .putInt("contrast", contrast.getProgress() + 50)
                    .putInt("saturation", saturation.getProgress())
                    .putBoolean("adBlockEnabled", adBlock.isChecked())
                    .apply();
            dialog.dismiss();
            if (previousAdBlock != isAdBlockEnabled()) {
                // Re-load to apply/remove request interception cleanly on every open tab.
                for (BrowserTab tab : new ArrayList<>(tabs)) {
                    if (tab.webView != null && tab.url != null && !"about:blank".equals(tab.url)) tab.webView.reload();
                    else if (tab.webView != null) injectEnhancement(tab);
                }
            } else {
                for (BrowserTab tab : tabs) injectEnhancement(tab);
            }
            statusView.setText("Settings applied · Ads/tracker blocking " + (isAdBlockEnabled() ? "on" : "off"));
        }));
        dialog.show();
    }

    private SeekBar addRangeSlider(LinearLayout panel, String name, int min, int max, int initial) {
        TextView label = sectionLabel(name + ": " + initial + "%");
        panel.addView(label);
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, initial - min)));
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(name + ": " + (progress + min) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        panel.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
        return seek;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(TEXT);
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(3), dp(8), dp(3), dp(2));
        return label;
    }

    private void addControl(LinearLayout panel, View control) {
        panel.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private int targetIndex(String target) {
        switch (target) {
            case "5k": return 1;
            case "8k": return 2;
            case "10k": return 3;
            case "16k": return 4;
            case "32k": return 5;
            default: return 0;
        }
    }

    private String targetForIndex(int index) {
        switch (index) {
            case 1: return "5k";
            case 2: return "8k";
            case 3: return "10k";
            case 4: return "16k";
            case 5: return "32k";
            default: return "4k";
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static final class AdBlocker {
        private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "googleads.g.doubleclick.net", "adservice.google.com", "adnxs.com",
                "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com",
                "scorecardresearch.com", "moatads.com", "pubmatic.com", "openx.net",
                "rubiconproject.com", "adform.net", "serving-sys.com", "amazon-adsystem.com",
                "casalemedia.com", "smartadserver.com", "3lift.com", "yieldmo.com",
                "smaato.net", "lijit.com", "media.net", "mgid.com", "exelator.com",
                "adcolony.com"
        ));

        static boolean isBlocked(String value) {
            if (value == null) return false;
            try {
                Uri uri = Uri.parse(value);
                String host = uri.getHost();
                if (host == null) return false;
                host = host.toLowerCase(Locale.ROOT);
                for (String domain : BLOCKED_DOMAINS) {
                    if (host.equals(domain) || host.endsWith("." + domain)) return true;
                }
                String path = (uri.getPath() == null ? "" : uri.getPath()).toLowerCase(Locale.ROOT);
                return path.contains("/pagead/") || path.contains("/adservice/") || path.contains("/adsystem/");
            } catch (Exception e) {
                return false;
            }
        }

        static WebResourceResponse emptyResponse() {
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
    }
}
