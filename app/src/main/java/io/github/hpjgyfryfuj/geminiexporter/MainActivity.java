package io.github.hpjgyfryfuj.geminiexporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.print.PdfPrinter;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String STATE_URL = "url";
    private static final String STATE_TEXT = "text";
    private static final String STATE_SUBJECT = "subject";

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+");
    private static final long EXTRACT_TIMEOUT_MS = 120_000;
    /** After this long without recognised messages the whole page can still be saved. */
    private static final long RECOGNITION_GRACE_MS = 10_000;

    private static final String HELP = ""
            + "Как сохранить чат из Gemini:\n\n"
            + "1. Откройте нужный чат в приложении Gemini.\n"
            + "2. Нажмите «Поделиться» (в меню ⋮ чата или под ответом) и создайте публичную ссылку.\n"
            + "3. В списке приложений выберите «Gemini Exporter».\n"
            + "4. Когда чат загрузится, нажмите «Скачать PDF» или «Скачать Markdown».\n\n"
            + "Можно также вставить ссылку вида g.co/gemini/share/… в поле выше и нажать «Открыть».\n\n"
            + "В файл попадают ваши сообщения, ответы Gemini и картинки. "
            + "Файлы сохраняются в папку «" + Downloads.DISPLAY_FOLDER + "».";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WebView webView;
    /** Off-screen page that is printed to PDF; kept alive while the system print dialog uses it. */
    private WebView printView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private View helpView;
    private TextView statusView;
    private Button pdfButton;
    private Button markdownButton;

    private String extractScript;
    private String currentUrl;
    private String sharedText;
    private String sharedSubject;
    /** Bumped on every new page so callbacks from an older page are ignored. */
    private int loadGeneration;
    private long pageFinishedAt;
    private boolean ready;
    private boolean exporting;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        extractScript = readAsset("extract.js");
        buildUi();
        if (savedInstanceState != null) {
            String url = savedInstanceState.getString(STATE_URL);
            String text = savedInstanceState.getString(STATE_TEXT);
            if (url != null) {
                openUrl(url);
            } else if (text != null) {
                showText(text, savedInstanceState.getString(STATE_SUBJECT));
            }
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_URL, currentUrl);
        outState.putString(STATE_TEXT, sharedText);
        outState.putString(STATE_SUBJECT, sharedSubject);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        webView.destroy();
        destroyPrintView();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ---- UI ----

    private void buildUi() {
        int pad = dp(16);
        int accent = getColor(R.color.accent);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        applyInsets(root);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(pad, pad, pad, dp(8));
        root.addView(title);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setPadding(pad, 0, pad - dp(4), 0);
        urlInput = new EditText(this);
        urlInput.setHint("Ссылка на чат: g.co/gemini/share/…");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            openFromInput();
            return true;
        });
        urlRow.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button openButton = new Button(this);
        openButton.setText("Открыть");
        openButton.setOnClickListener(v -> openFromInput());
        urlRow.addView(openButton);
        root.addView(urlRow);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setProgressTintList(ColorStateList.valueOf(accent));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        progressBar.setVisibility(View.INVISIBLE);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        FrameLayout body = new FrameLayout(this);
        webView = new WebView(this);
        setUpWebView();
        webView.setVisibility(View.GONE);
        body.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView help = new ScrollView(this);
        TextView helpText = new TextView(this);
        helpText.setText(HELP);
        helpText.setTextSize(16);
        helpText.setLineSpacing(0, 1.2f);
        helpText.setPadding(pad, pad, pad, pad);
        help.addView(helpText);
        helpView = help;
        body.addView(help, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setPadding(pad, dp(8), pad, 0);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(pad - dp(4), dp(4), pad - dp(4), dp(8));
        pdfButton = new Button(this);
        pdfButton.setText("Скачать PDF");
        pdfButton.setOnClickListener(v -> export(true));
        markdownButton = new Button(this);
        markdownButton.setText("Скачать Markdown");
        markdownButton.setOnClickListener(v -> export(false));
        for (Button b : new Button[]{pdfButton, markdownButton}) {
            b.setBackgroundTintList(ColorStateList.valueOf(accent));
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(buttons);

        setContentView(root);
        updateButtons();
    }

    /** The window is drawn edge to edge (forced from Android 15), so keep content clear of the bars. */
    @SuppressWarnings("deprecation")
    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
    }

    private void setUpWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!request.isForMainFrame() || isGoogleHost(uri.getHost())) return false;
                // Links inside answers open in the browser instead of replacing the chat.
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageFinishedAt = SystemClock.elapsedRealtime();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && currentUrl != null) {
                    setStatus("Не удалось открыть страницу: " + error.getDescription()
                            + ". Проверьте интернет и ссылку.");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (exporting) return;
                progressBar.setIndeterminate(false);
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    private void setStatus(String text) {
        statusView.setText(text);
        statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void updateButtons() {
        boolean enabled = ready && !exporting;
        for (Button b : new Button[]{pdfButton, markdownButton}) {
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private void setExporting(boolean on, String status) {
        exporting = on;
        progressBar.setIndeterminate(on);
        progressBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (status != null) setStatus(status);
        updateButtons();
    }

    // ---- opening a chat ----

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (text == null || text.toString().trim().isEmpty()) return;
            String url = findGeminiUrl(text.toString());
            if (url != null) {
                openUrl(url);
            } else {
                showText(text.toString(), subject);
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            openUrl(intent.getData().toString());
        }
    }

    private void openFromInput() {
        String text = urlInput.getText().toString().trim();
        if (text.isEmpty()) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString().trim();
            }
        }
        String url = findGeminiUrl(text);
        if (url == null && !text.isEmpty() && !text.contains("://")) url = findGeminiUrl("https://" + text);
        if (url == null) {
            Toast.makeText(this, "Это не похоже на ссылку на чат Gemini", Toast.LENGTH_LONG).show();
            return;
        }
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        urlInput.clearFocus();
        openUrl(url);
    }

    private void openUrl(String url) {
        loadGeneration++;
        currentUrl = url;
        sharedText = null;
        sharedSubject = null;
        ready = false;
        pageFinishedAt = 0;
        urlInput.setText(url);
        showPage();
        setStatus("Загружаю чат…");
        updateButtons();
        webView.loadUrl(url);
        watchForMessages(loadGeneration);
    }

    /** Shared text without a chat link (for example one copied answer) is saved as it is. */
    private void showText(String text, String subject) {
        loadGeneration++;
        currentUrl = null;
        sharedText = text;
        sharedSubject = subject;
        ready = true;
        urlInput.setText("");
        showPage();
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width\">"
                + "<style>body{font-family:sans-serif;padding:12px;line-height:1.5}</style></head><body>"
                + Chat.fromText(text, subject).turns.get(0).html + "</body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        setStatus("Получен текст без ссылки на чат — его можно сохранить как есть.");
        updateButtons();
    }

    private void showPage() {
        helpView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    /** Polls the page until the conversation shows up, then enables the download buttons. */
    private void watchForMessages(int generation) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation != loadGeneration) return;
                handler.postDelayed(this, 1500);
                if (!resumed || exporting) return;
                webView.evaluateJavascript(extractScript + "\n;window.__geminiExporter.count()", value -> {
                    if (generation != loadGeneration || exporting) return;
                    onMessageCount(parseInt(value));
                });
            }
        }, 1000);
    }

    private void onMessageCount(int count) {
        if (count > 0) {
            ready = true;
            setStatus("Чат загружен: " + count + " " + plural(count, "сообщение", "сообщения", "сообщений")
                    + ". Можно скачивать.");
        } else if (pageFinishedAt > 0 && SystemClock.elapsedRealtime() - pageFinishedAt > RECOGNITION_GRACE_MS) {
            ready = true;
            setStatus("Сообщения чата не распознаны — будет сохранена вся страница целиком.");
        }
        updateButtons();
    }

    // ---- exporting ----

    private void export(boolean pdf) {
        if (exporting || !ready) return;
        if (sharedText != null) {
            exportChat(Chat.fromText(sharedText, sharedSubject), pdf);
            return;
        }
        int generation = loadGeneration;
        setExporting(true, "Собираю чат…");
        webView.evaluateJavascript(extractScript + "\n;window.__geminiExporter.start()", null);
        long deadline = SystemClock.elapsedRealtime() + EXTRACT_TIMEOUT_MS;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Runnable poll = this;
                if (generation != loadGeneration) {
                    setExporting(false, null);
                    return;
                }
                webView.evaluateJavascript(
                        "window.__geminiExporter ? window.__geminiExporter.poll() : 'ERR:страница перезагрузилась'",
                        value -> {
                            if (generation != loadGeneration) return;
                            String result = decodeJsString(value);
                            if (result == null) {
                                if (SystemClock.elapsedRealtime() > deadline) {
                                    fail("Чат собирается слишком долго. Попробуйте ещё раз.");
                                } else {
                                    handler.postDelayed(poll, 400);
                                }
                            } else if (result.startsWith("ERR:")) {
                                fail("Не удалось прочитать чат: " + result.substring(4));
                            } else {
                                try {
                                    exportChat(Chat.fromJson(result), pdf);
                                } catch (JSONException e) {
                                    fail("Не удалось прочитать чат: " + e.getMessage());
                                }
                            }
                        });
            }
        }, 400);
    }

    private void exportChat(Chat chat, boolean pdf) {
        setExporting(true, chat.images.isEmpty() ? "Сохраняю…" : "Загружаю картинки…");
        String userAgent = webView.getSettings().getUserAgentString();
        ContentResolver resolver = getContentResolver();
        File printDir = new File(getCacheDir(), "print");
        Date now = new Date();
        worker.execute(() -> {
            List<ChatExporter.Image> images = ChatExporter.loadImages(chat.images, userAgent);
            int missing = ChatExporter.countMissing(images);
            try {
                if (pdf) {
                    File page = ChatExporter.writePrintPage(printDir, chat, images, now);
                    String base = ChatExporter.fileBase(chat, now);
                    handler.post(() -> renderPdf(page, base, missing));
                } else {
                    ChatExporter.Saved saved = ChatExporter.saveMarkdown(resolver, chat, images, now);
                    handler.post(() -> onSaved(saved, missing));
                }
            } catch (IOException | RuntimeException e) {
                handler.post(() -> fail("Не удалось сохранить файл: " + e.getMessage()));
            }
        });
    }

    private void renderPdf(File page, String base, int missing) {
        if (isDestroyed()) return;
        setExporting(true, "Создаю PDF…");
        destroyPrintView();
        printView = new WebView(this);
        WebSettings settings = printView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(true);
        printView.setWebViewClient(new WebViewClient() {
            private boolean started;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (started) return;
                started = true;
                handler.postDelayed(() -> writePdf(view, base, missing), 500);
            }
        });
        printView.loadUrl(Uri.fromFile(page).toString());
    }

    private void writePdf(WebView view, String base, int missing) {
        if (isDestroyed() || view != printView) return;
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                .setMinMargins(new PrintAttributes.Margins(500, 500, 500, 500))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();
        String name = base + ".pdf";
        ContentResolver resolver = getContentResolver();
        Uri uri = null;
        ParcelFileDescriptor out = null;
        try {
            uri = Downloads.create(resolver, null, name, "application/pdf");
            out = resolver.openFileDescriptor(uri, "w");
            if (out == null) throw new IOException("Cannot open " + uri);
            Uri target = uri;
            ParcelFileDescriptor fd = out;
            PdfPrinter.print(view.createPrintDocumentAdapter(base), attributes, fd, new PdfPrinter.Callback() {
                @Override
                public void onSuccess() {
                    closeQuietly(fd);
                    Downloads.publish(resolver, target);
                    onSaved(new ChatExporter.Saved(target, "application/pdf",
                            Downloads.DISPLAY_FOLDER + "/" + name), missing);
                }

                @Override
                public void onFailure(String message) {
                    closeQuietly(fd);
                    Downloads.delete(resolver, target);
                    printWithDialog(view, base);
                }
            });
        } catch (Throwable t) {
            // Includes the linkage errors thrown where direct PDF writing is blocked.
            closeQuietly(out);
            if (uri != null) Downloads.delete(resolver, uri);
            printWithDialog(view, base);
        }
    }

    private void printWithDialog(WebView view, String base) {
        if (isDestroyed()) return;
        PrintManager printManager = getSystemService(PrintManager.class);
        if (printManager == null) {
            fail("На этом телефоне недоступно сохранение в PDF.");
            return;
        }
        printManager.print(base, view.createPrintDocumentAdapter(base),
                new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
        setExporting(false, "В окне печати выберите «Сохранить как PDF» и нажмите кнопку PDF.");
    }

    private void onSaved(ChatExporter.Saved saved, int missing) {
        setExporting(false, "Сохранено: " + saved.location);
        if (isFinishing() || isDestroyed()) return;
        String message = "Файл сохранён:\n" + saved.location;
        if (missing > 0) {
            message += "\n\nНе удалось загрузить " + missing + " "
                    + plural(missing, "картинку", "картинки", "картинок") + ".";
        }
        new AlertDialog.Builder(this)
                .setTitle("Готово")
                .setMessage(message)
                .setPositiveButton("Открыть", (d, w) -> openFile(saved))
                .setNeutralButton("Поделиться", (d, w) -> shareFile(saved))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void fail(String message) {
        setExporting(false, message);
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Ошибка")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void openFile(ChatExporter.Saved saved) {
        String[] types = ChatExporter.MARKDOWN_MIME.equals(saved.mime)
                ? new String[]{saved.mime, "text/plain"}
                : new String[]{saved.mime};
        for (String type : types) {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(saved.uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next type.
            }
        }
        Toast.makeText(this, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_LONG).show();
    }

    private void shareFile(ChatExporter.Saved saved) {
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(saved.mime)
                .putExtra(Intent.EXTRA_STREAM, saved.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Поделиться файлом"));
    }

    private void destroyPrintView() {
        if (printView != null) {
            printView.destroy();
            printView = null;
        }
    }

    // ---- helpers ----

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "Нет приложения, чтобы открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    static String findGeminiUrl(String text) {
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,;:!?)\\]}»]+$", "");
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if ((host.equals("g.co") && path.startsWith("/gemini"))
                    || host.equals("gemini.google.com") || host.equals("gemini.google")) {
                return url;
            }
        }
        return null;
    }

    private static boolean isGoogleHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        return host.equals("g.co")
                || host.equals("gemini.google")
                || host.matches("(.+\\.)?google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})")
                || host.endsWith(".gstatic.com")
                || host.endsWith(".googleusercontent.com");
    }

    private static String decodeJsString(String value) {
        if (value == null) return null;
        try {
            Object decoded = new JSONTokener(value).nextValue();
            return decoded instanceof String ? (String) decoded : null;
        } catch (JSONException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    private static String plural(int n, String one, String few, String many) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try {
            fd.close();
        } catch (IOException ignored) {
            // Already closed or never opened.
        }
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
