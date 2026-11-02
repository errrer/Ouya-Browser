package com.ouyabrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OUYA Browser: a light Android 4.1.2 client. Modern pages are rendered by the
 * companion remote-engine service; the local WebView is only a compatibility fallback.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "ouya_browser_settings";
    private static final int REMOTE_WIDTH = 1280;
    private static final int REMOTE_HEIGHT = 720;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private volatile String serverBase = "";
    private volatile String token = "";
    private volatile boolean remoteMode = true;
    private volatile boolean running = true;
    private boolean settingsOpen = false;
    private AlertDialog settingsDialog;

    private LinearLayout root;
    private EditText address;
    private ImageView remoteImage;
    private WebView localWeb;
    private TextView status;
    private TextView emptyMessage;
    private TextView cursorMarker;
    private float cursorX = REMOTE_WIDTH / 2f;
    private float cursorY = REMOTE_HEIGHT / 2f;
    private Bitmap lastBitmap;
    private float touchStartX;
    private float touchStartY;
    private long lastPointerSend;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverBase = prefs.getString("server", "");
        token = prefs.getString("token", "");
        remoteMode = prefs.getBoolean("remote", true);

        buildInterface();
        setMode(remoteMode);
        startFrameLoop();
    }

    private void buildInterface() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 14, 22));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(7), dp(12), dp(6));
        top.setBackgroundColor(Color.rgb(13, 23, 35));

        TextView logo = new TextView(this);
        logo.setText("OUYA  BROWSER");
        logo.setTextColor(Color.rgb(55, 222, 204));
        logo.setTextSize(17);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(42)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(16);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(Color.rgb(145, 160, 177));
        address.setHint("Search or enter website address");
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType( android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setBackground(rounded(Color.rgb(25, 39, 56), dp(7), Color.rgb(48, 69, 88)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        addressParams.rightMargin = dp(8);
        top.addView(address, addressParams);
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                goAddress();
                hideKeyboard();
                return true;
            }
            return false;
        });

        top.addView(makeButton("GO", () -> {
            goAddress();
            hideKeyboard();
        }), new LinearLayout.LayoutParams(dp(58), dp(42)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(78), dp(42));
        settingsParams.leftMargin = dp(7);
        Button setupButton = makeButton("SETUP", this::showSettings);
        top.addView(setupButton, settingsParams);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(12), dp(4), dp(12), dp(4));
        controls.setBackgroundColor(Color.rgb(10, 18, 28));
        addControl(controls, "HOME", this::goHome);
        addControl(controls, "BACK", this::goBack);
        addControl(controls, "NEXT", this::goForward);
        addControl(controls, "RELOAD", this::reload);
        addControl(controls, "REMOTE", () -> setMode(true));
        addControl(controls, "LOCAL", () -> setMode(false));
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(48)));

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.rgb(5, 9, 15));

        remoteImage = new ImageView(this);
        remoteImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        remoteImage.setFocusable(true);
        remoteImage.setFocusableInTouchMode(true);
        remoteImage.setBackgroundColor(Color.rgb(5, 9, 15));
        remoteImage.setOnTouchListener((view, event) -> onRemoteTouch(event));
        content.addView(remoteImage, new FrameLayout.LayoutParams(-1, -1));

        cursorMarker = new TextView(this);
        cursorMarker.setText("●");
        cursorMarker.setTextSize(21);
        cursorMarker.setTextColor(Color.WHITE);
        cursorMarker.setShadowLayer(dp(4), 0, 0, Color.BLACK);
        cursorMarker.setGravity(Gravity.CENTER);
        cursorMarker.setVisibility(View.GONE);
        content.addView(cursorMarker, new FrameLayout.LayoutParams(dp(28), dp(28)));

        localWeb = new WebView(this);
        localWeb.setBackgroundColor(Color.WHITE);
        WebSettings settings = localWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        localWeb.setWebChromeClient(new WebChromeClient());
        localWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                address.setText(url);
                setStatus("LOCAL • Loading " + url);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                address.setText(url);
                setStatus("LOCAL • " + url);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        content.addView(localWeb, new FrameLayout.LayoutParams(-1, -1));

        emptyMessage = new TextView(this);
        emptyMessage.setTextColor(Color.WHITE);
        emptyMessage.setTextSize(20);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setPadding(dp(30), dp(20), dp(30), dp(20));
        emptyMessage.setText("REMOTE ENGINE NOT CONFIGURED\n\nChoose SETUP and enter the address of the computer running OUYA Browser Remote Engine.\n\nThe OUYA stays lightweight. Chromium runs on the other machine.");
        emptyMessage.setBackground(rounded(Color.rgb(13, 23, 35), dp(10), Color.rgb(39, 64, 81)));
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(dp(620), -2, Gravity.CENTER);
        content.addView(emptyMessage, messageParams);

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(12), dp(3), dp(12), dp(3));
        footer.setBackgroundColor(Color.rgb(13, 23, 35));
        status = new TextView(this);
        status.setTextColor(Color.rgb(184, 200, 215));
        status.setTextSize(12);
        status.setText("Ready • O/ A/ U/ Y controller input supported through Android gamepad events");
        footer.addView(status, new LinearLayout.LayoutParams(0, dp(30), 1f));
        TextView legend = new TextView(this);
        legend.setText("O CLICK   A BACK   U RELOAD   Y URL   L3 CLICK   R3 MODE");
        legend.setTextColor(Color.rgb(55, 222, 204));
        legend.setTextSize(11);
        footer.addView(legend, new LinearLayout.LayoutParams(-2, dp(30)));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(36)));

        setContentView(root);
        if (configured()) remoteImage.requestFocus();
        else setupButton.requestFocus();
    }

    private void addControl(LinearLayout row, String label, Runnable action) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(76), dp(37));
        p.rightMargin = dp(7);
        row.addView(makeButton(label, action), p);
    }

    private Button makeButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(true);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(rounded(Color.rgb(25, 43, 60), dp(7), Color.rgb(55, 83, 105)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setMode(boolean remote) {
        remoteMode = remote;
        if (remoteImage == null) return;
        remoteImage.setVisibility(remote ? View.VISIBLE : View.GONE);
        localWeb.setVisibility(remote ? View.GONE : View.VISIBLE);
        if (remote) remoteImage.requestFocus();
        cursorMarker.setVisibility(remote && configured() ? View.VISIBLE : View.GONE);
        emptyMessage.setVisibility(remote && !configured() ? View.VISIBLE : View.GONE);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("remote", remote).apply();
        setStatus(remote ? "REMOTE • " + (configured() ? "Connecting to engine" : "Setup required") : "LOCAL • Built-in Android WebView");
        if (!remote) {
            localWeb.requestFocus();
            if (localWeb.getUrl() == null) localWeb.loadUrl("https://github.com");
        }
        if (remote && configured()) {
            emptyMessage.setVisibility(View.GONE);
            remoteImage.requestFocus();
            updateCursorMarker();
            if (lastBitmap == null) setStatus("REMOTE • Waiting for first frame");
        }
    }

    private boolean configured() {
        return serverBase != null && serverBase.length() > 6 && token != null && token.length() >= 8;
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView tip = new TextView(this);
        tip.setText("Enter the LAN address of the computer running the remote engine, then its access token. Example: http://192.168.1.20:8765");
        tip.setTextSize(14);
        panel.addView(tip);

        EditText serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setHint("http://192.168.1.20:8765");
        serverInput.setText(serverBase);
        panel.addView(serverInput);

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Remote engine access token");
        tokenInput.setText(token);
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(tokenInput);

        settingsOpen = true;
        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("OUYA Browser setup")
                .setView(panel)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE & CONNECT", (dialog, which) -> {
                    serverBase = serverInput.getText().toString().trim();
                    while (serverBase.endsWith("/")) serverBase = serverBase.substring(0, serverBase.length() - 1);
                    token = tokenInput.getText().toString().trim();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("server", serverBase)
                            .putString("token", token)
                            .apply();
                    settingsOpen = false;
                    setMode(true);
                    if (configured()) {
                        emptyMessage.setVisibility(View.GONE);
                        postJson("/navigate", json("url", "https://github.com"));
                        setStatus("REMOTE • Connecting to " + serverBase);
                    } else {
                        emptyMessage.setVisibility(View.VISIBLE);
                        toast("Enter a server address and a token (8+ characters).");
                    }
                }).setOnDismissListener(dialog -> {
                    settingsOpen = false;
                    settingsDialog = null;
                }).create();
        settingsDialog.show();
    }

    private void goAddress() {
        String target = normalizeAddress(address.getText().toString());
        if (target.length() == 0) return;
        address.setText(target);
        if (remoteMode) {
            if (!configured()) {
                showSettings();
                return;
            }
            postJson("/navigate", json("url", target));
            emptyMessage.setVisibility(View.GONE);
            setStatus("REMOTE • Opening " + target);
            remoteImage.requestFocus();
        } else {
            localWeb.loadUrl(target);
        }
    }

    private String normalizeAddress(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() == 0) return "";
        if (text.matches("(?i)^https?://.*")) return text;
        if (text.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$")) return "https://" + text;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return "https://github.com";
        }
    }

    private void goHome() {
        address.setText("https://github.com");
        goAddress();
    }

    private void goBack() {
        if (remoteMode) postJson("/history", json("action", "back"));
        else if (localWeb.canGoBack()) localWeb.goBack();
    }

    private void goForward() {
        if (remoteMode) postJson("/history", json("action", "forward"));
        else if (localWeb.canGoForward()) localWeb.goForward();
    }

    private void reload() {
        if (remoteMode) postJson("/history", json("action", "reload"));
        else localWeb.reload();
    }

    private JSONObject json(String key, String value) {
        JSONObject object = new JSONObject();
        try { object.put(key, value); } catch (Exception ignored) { }
        return object;
    }

    private void sendInput(JSONObject data) {
        if (!configured() || !remoteMode) return;
        postJson("/input", data);
    }

    private void postJson(String path, JSONObject data) {
        if (!configured()) {
            main.post(() -> setStatus("REMOTE • Configure the engine in SETUP first"));
            return;
        }
        actions.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(serverBase + path);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = data.toString().getBytes("UTF-8");
                OutputStream output = connection.getOutputStream();
                output.write(body);
                output.flush();
                output.close();
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    main.post(() -> {
                        if (emptyMessage != null) emptyMessage.setVisibility(View.GONE);
                    });
                } else {
                    main.post(() -> setStatus("REMOTE • Engine returned HTTP " + code));
                }
            } catch (Exception e) {
                main.post(() -> setStatus("REMOTE • Connection error. Check SETUP and server."));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void startFrameLoop() {
        Thread thread = new Thread(() -> {
            while (running) {
                if (remoteMode && configured()) {
                    HttpURLConnection connection = null;
                    try {
                        URL url = new URL(serverBase + "/frame.jpg");
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(3000);
                        connection.setReadTimeout(4500);
                        connection.setRequestProperty("Authorization", "Bearer " + token);
                        connection.setRequestProperty("Cache-Control", "no-cache");
                        if (connection.getResponseCode() == 200) {
                            BitmapFactory.Options options = new BitmapFactory.Options();
                            options.inPreferredConfig = Bitmap.Config.RGB_565;
                            options.inPurgeable = true;
                            InputStream input = connection.getInputStream();
                            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                            input.close();
                            if (bitmap != null) {
                                main.post(() -> showFrame(bitmap));
                            }
                        }
                    } catch (Exception ignored) {
                        // Keep the UI alive; the next poll retries the connection.
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
                try { Thread.sleep(remoteMode ? 300 : 900); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }, "OUYA-Frame-Receiver");
        thread.setDaemon(true);
        thread.start();
    }

    private void showFrame(Bitmap bitmap) {
        if (isFinishing()) {
            bitmap.recycle();
            return;
        }
        Bitmap old = lastBitmap;
        lastBitmap = bitmap;
        remoteImage.setImageBitmap(bitmap);
        updateCursorMarker();
        if (emptyMessage.getVisibility() == View.VISIBLE) emptyMessage.setVisibility(View.GONE);
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
        setStatus("REMOTE • Live Chromium session • " + bitmap.getWidth() + "×" + bitmap.getHeight());
    }

    private boolean onRemoteTouch(MotionEvent event) {
        if (!remoteMode || !configured()) return false;
        float[] point = toRemotePoint(event.getX(), event.getY());
        if (point == null) return true;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getX();
            touchStartY = event.getY();
            cursorX = point[0];
            cursorY = point[1];
            sendMouseMove(point[0], point[1]);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(event.getX() - touchStartX) + Math.abs(event.getY() - touchStartY) > dp(5)) {
                cursorX = point[0];
                cursorY = point[1];
                sendMouseMove(point[0], point[1]);
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            sendMouseClick(point[0], point[1]);
            return true;
        }
        return true;
    }

    private float[] toRemotePoint(float x, float y) {
        if (lastBitmap == null || remoteImage.getWidth() == 0 || remoteImage.getHeight() == 0) return null;
        float scale = Math.min((float) remoteImage.getWidth() / lastBitmap.getWidth(),
                (float) remoteImage.getHeight() / lastBitmap.getHeight());
        float shownW = lastBitmap.getWidth() * scale;
        float shownH = lastBitmap.getHeight() * scale;
        float left = (remoteImage.getWidth() - shownW) / 2f;
        float top = (remoteImage.getHeight() - shownH) / 2f;
        float nx = Math.max(0f, Math.min(1f, (x - left) / shownW));
        float ny = Math.max(0f, Math.min(1f, (y - top) / shownH));
        return new float[]{nx * REMOTE_WIDTH, ny * REMOTE_HEIGHT};
    }

    private void sendMouseMove(float x, float y) {
        long now = System.currentTimeMillis();
        if (now - lastPointerSend < 70) return;
        lastPointerSend = now;
        cursorX = Math.max(0, Math.min(REMOTE_WIDTH, x));
        cursorY = Math.max(0, Math.min(REMOTE_HEIGHT, y));
        updateCursorMarker();
        JSONObject object = new JSONObject();
        try {
            object.put("type", "move");
            object.put("x", (int) x);
            object.put("y", (int) y);
        } catch (Exception ignored) { }
        sendInput(object);
    }

    private void updateCursorMarker() {
        if (cursorMarker == null || remoteImage == null || lastBitmap == null) return;
        int viewW = remoteImage.getWidth();
        int viewH = remoteImage.getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        float scale = Math.min((float) viewW / lastBitmap.getWidth(), (float) viewH / lastBitmap.getHeight());
        float shownW = lastBitmap.getWidth() * scale;
        float shownH = lastBitmap.getHeight() * scale;
        float left = (viewW - shownW) / 2f;
        float top = (viewH - shownH) / 2f;
        int px = Math.round(left + cursorX / REMOTE_WIDTH * shownW - dp(14));
        int py = Math.round(top + cursorY / REMOTE_HEIGHT * shownH - dp(14));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cursorMarker.getLayoutParams();
        params.leftMargin = px;
        params.topMargin = py;
        cursorMarker.setLayoutParams(params);
    }

    private void sendMouseClick(float x, float y) {
        JSONObject object = new JSONObject();
        try {
            object.put("type", "click");
            object.put("x", (int) x);
            object.put("y", (int) y);
        } catch (Exception ignored) { }
        sendInput(object);
    }

    private void sendWheel(int delta) {
        JSONObject object = new JSONObject();
        try { object.put("type", "wheel"); object.put("deltaY", delta); }
        catch (Exception ignored) { }
        sendInput(object);
    }

    private void sendKey(String key) {
        JSONObject object = new JSONObject();
        try { object.put("type", "key"); object.put("key", key); }
        catch (Exception ignored) { }
        sendInput(object);
    }

    private void sendText(String text) {
        if (text == null || text.length() == 0) return;
        JSONObject object = new JSONObject();
        try { object.put("type", "text"); object.put("text", text); }
        catch (Exception ignored) { }
        sendInput(object);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(address.getWindowToken(), 0);
            remoteImage.requestFocus();
        } catch (Exception ignored) { }
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int pressed = event.getKeyCode();
            // On the original OUYA layout, Android BUTTON_A is the physical O button.
            if (!settingsOpen && address != null && address.hasFocus() && pressed == KeyEvent.KEYCODE_BUTTON_A) {
                goAddress();
                hideKeyboard();
                return true;
            }
            if (pressed == KeyEvent.KEYCODE_BUTTON_THUMBR && !settingsOpen) {
                setMode(!remoteMode);
                return true;
            }
            if (settingsOpen && settingsDialog != null) {
                if (pressed == KeyEvent.KEYCODE_BUTTON_A || pressed == KeyEvent.KEYCODE_DPAD_CENTER) {
                    View focused = settingsDialog.getWindow() == null ? null : settingsDialog.getWindow().getDecorView().findFocus();
                    if (focused != null && focused.isClickable()) focused.performClick();
                    return true;
                }
                if (pressed == KeyEvent.KEYCODE_BUTTON_B || pressed == KeyEvent.KEYCODE_BACK) {
                    settingsDialog.dismiss();
                    return true;
                }
            }
            if (remoteMode && !configured() && pressed == KeyEvent.KEYCODE_BUTTON_A) {
                View focused = getWindow().getDecorView().findFocus();
                if (focused != null && focused.isClickable()) focused.performClick();
                else showSettings();
                return true;
            }
            if (!remoteMode && !settingsOpen) {
                if (pressed == KeyEvent.KEYCODE_BUTTON_B || pressed == KeyEvent.KEYCODE_BACK) {
                    if (localWeb.canGoBack()) localWeb.goBack(); else onBackPressed();
                    return true;
                }
                if (pressed == KeyEvent.KEYCODE_BUTTON_X) { localWeb.reload(); return true; }
                if (pressed == KeyEvent.KEYCODE_BUTTON_Y) {
                    address.requestFocus();
                    address.setSelection(address.length());
                    ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
                    return true;
                }
                if (pressed == KeyEvent.KEYCODE_BUTTON_L1) { goBack(); return true; }
                if (pressed == KeyEvent.KEYCODE_BUTTON_R1) { goForward(); return true; }
                if (pressed == KeyEvent.KEYCODE_MENU) { showSettings(); return true; }
                if (pressed == KeyEvent.KEYCODE_BUTTON_START) { goHome(); return true; }
            }
        }
        if (!remoteMode || settingsOpen || address == null || address.hasFocus() || (remoteImage != null && !remoteImage.hasFocus())) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                sendWheel(-460); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sendWheel(460); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                sendKey("ArrowLeft"); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sendKey("ArrowRight"); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_ENTER:
                if (lastBitmap != null) sendMouseClick(cursorX, cursorY);
                else sendKey("Enter");
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                goBack(); return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                reload(); return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                address.requestFocus();
                address.setSelection(address.length());
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                goBack(); return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                goForward(); return true;
            case KeyEvent.KEYCODE_MENU:
                showSettings(); return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                goHome(); return true;
            case KeyEvent.KEYCODE_DEL:
                sendKey("Backspace"); return true;
            case KeyEvent.KEYCODE_TAB:
                sendKey("Tab"); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                sendWheel(620); return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                sendWheel(-620); return true;
        }
        if (event.getUnicodeChar() != 0 && !event.isCtrlPressed() && !event.isAltPressed()) {
            sendText(String.valueOf((char) event.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (remoteMode && configured()) {
            if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK
                    && event.getAction() == MotionEvent.ACTION_MOVE) {
                float stickX = event.getAxisValue(MotionEvent.AXIS_X);
                float stickY = event.getAxisValue(MotionEvent.AXIS_Y);
                if (Math.abs(stickX) > 0.12f || Math.abs(stickY) > 0.12f) {
                    cursorX = Math.max(0, Math.min(REMOTE_WIDTH, cursorX + stickX * 28f));
                    cursorY = Math.max(0, Math.min(REMOTE_HEIGHT, cursorY + stickY * 28f));
                    updateCursorMarker();
                    sendMouseMove(cursorX, cursorY);
                }
                return true;
            }
            if ((event.getSource() & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE) {
                if (event.getAction() == MotionEvent.ACTION_HOVER_MOVE || event.getAction() == MotionEvent.ACTION_MOVE) {
                    int[] location = new int[2];
                    remoteImage.getLocationOnScreen(location);
                    float[] point = toRemotePoint(event.getRawX() - location[0], event.getRawY() - location[1]);
                    if (point != null) sendMouseMove(point[0], point[1]);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                    float axis = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (axis != 0) sendWheel((int) (-axis * 380));
                    return true;
                }
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (remoteMode) goBack();
        else if (localWeb.canGoBack()) localWeb.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;
        actions.shutdownNow();
        if (localWeb != null) {
            localWeb.stopLoading();
            localWeb.destroy();
        }
        if (lastBitmap != null && !lastBitmap.isRecycled()) lastBitmap.recycle();
        super.onDestroy();
    }
}
