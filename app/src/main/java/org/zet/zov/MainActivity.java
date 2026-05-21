package org.zet.zov;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.system.Os;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MainActivity extends AppCompatActivity {
    private TextView logConsole;
    private TextView statusText;
    private TextView updateNoticeText;
    private TextView versionInfoText;
    private ScrollView logScroll;
    private EditText inputField;
    private Button followOutputBtn;
    private Spinner sessionSpinner;
    private Spinner terminalSpinner;
    private View actionPanel;
    private ArrayAdapter<String> sessionAdapter;
    private ArrayAdapter<String> terminalAdapter;
    private final ArrayList<String> sessionProfiles = new ArrayList<>();
    private final ArrayList<String> terminalProfiles = new ArrayList<>();
    private final Map<String, Process> terminalProcesses = new HashMap<>();
    private Process currentProcess;
    private File baseDir;
    private File rootfsDir;
    private File supportDir;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean waitingForInlineBot = false;
    private boolean waitingForSessionName = false;
    private boolean waitingForTerminalName = false;
    private boolean manualStop = false;
    private boolean botAutoRestartEnabled = false;
    private boolean botSupervisorActive = false;
    private boolean followOutput = true;
    private boolean scrollScheduled = false;
    private Thread metricsThread;
    private Thread keepAliveWatchdogThread;
    private volatile boolean keepAliveWatchdogRunning = false;
    private long lastCpuTotal = 0;
    private long lastCpuIdle = 0;
    private double lastCpuPercent = -1;
    private String lastKeepAliveSignature = "";
    private String lastUpdateCheckLabel = "never";
    private static final String SUPPORT_URL = "https://t.me/herokuapk";
    private static final String GITHUB_REPO_URL = "https://github.com/ziwupa/heroku-host-apk";
    private static final String GITHUB_RELEASES_URL = "https://github.com/ziwupa/heroku-host-apk/releases/latest";
    private static final String REMOTE_BUILD_GRADLE_URL = "https://raw.githubusercontent.com/ziwupa/heroku-host-apk/main/app/build.gradle";
    private static final int MAX_LOG_CHARS = 90000;
    private static final String PATCH_MARKER = ".herokuapk_patch_v33";

    private static final String UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logConsole = findViewById(R.id.logConsole);
        statusText = findViewById(R.id.statusText);
        updateNoticeText = findViewById(R.id.updateNoticeText);
        versionInfoText = findViewById(R.id.versionInfoText);
        logScroll = findViewById(R.id.logScroll);
        inputField = findViewById(R.id.inputField);
        followOutputBtn = findViewById(R.id.followOutputBtn);
        sessionSpinner = findViewById(R.id.sessionSpinner);
        terminalSpinner = findViewById(R.id.terminalSpinner);
        actionPanel = findViewById(R.id.actionPanel);
        Button menuToggleBtn = findViewById(R.id.menuToggleBtn);
        Button menuCloseBtn = findViewById(R.id.menuCloseBtn);
        Button installLinuxBtn = findViewById(R.id.installLinuxBtn);
        Button installHerokuBtn = findViewById(R.id.installHerokuBtn);
        Button startBotBtn = findViewById(R.id.startBotBtn);
        Button terminalBtn = findViewById(R.id.terminalBtn);
        Button stopBtn = findViewById(R.id.stopBtn);
        Button copyLogsBtn = findViewById(R.id.copyLogsBtn);
        Button supportBtn = findViewById(R.id.supportBtn);
        Button addSessionBtn = findViewById(R.id.addSessionBtn);
        Button checkStatusBtn = findViewById(R.id.checkStatusBtn);
        Button repairBtn = findViewById(R.id.repairBtn);
        Button updateHerokuBtn = findViewById(R.id.updateHerokuBtn);
        Button reapplyPatchesBtn = findViewById(R.id.reapplyPatchesBtn);
        Button checkUpdatesBtn = findViewById(R.id.checkUpdatesBtn);
        Button addTerminalBtn = findViewById(R.id.addTerminalBtn);
        Button stopTerminalBtn = findViewById(R.id.stopTerminalBtn);
        Button clearLogsBtn = findViewById(R.id.clearLogsBtn);
        Button githubBtn = findViewById(R.id.githubBtn);
        Button bottomBtn = findViewById(R.id.bottomBtn);
        Button sendInputBtn = findViewById(R.id.sendInputBtn);

        baseDir = new File(getFilesDir(), "userland");
        rootfsDir = new File(baseDir, "rootfs");
        supportDir = new File(baseDir, "support");
        baseDir.mkdirs();
        supportDir.mkdirs();
        requestBackgroundWorkPermission();
        startHostMetricsWriter();
        startKeepAliveWatchdog();
        loadSessionProfiles();
        setupSessionMenu();
        loadTerminalProfiles();
        setupTerminalMenu();

        menuToggleBtn.setOnClickListener(v -> toggleMenu());
        menuCloseBtn.setOnClickListener(v -> closeMenu());
        installLinuxBtn.setOnClickListener(v -> runTask(this::installLinux));
        installHerokuBtn.setOnClickListener(v -> runTask(this::installHeroku));
        startBotBtn.setOnClickListener(v -> startInteractiveBot());
        terminalBtn.setOnClickListener(v -> startTerminalSession());
        stopBtn.setOnClickListener(v -> stopCurrentProcess());
        copyLogsBtn.setOnClickListener(v -> copyLogs());
        supportBtn.setOnClickListener(v -> openSupportChat());
        addSessionBtn.setOnClickListener(v -> askSessionName());
        checkStatusBtn.setOnClickListener(v -> runDiagnostics());
        repairBtn.setOnClickListener(v -> runTask(this::repairRuntime));
        updateHerokuBtn.setOnClickListener(v -> updateHeroku());
        reapplyPatchesBtn.setOnClickListener(v -> reapplyPatches());
        checkUpdatesBtn.setOnClickListener(v -> {
            log("[UPDATE] Manual check requested");
            checkForUpdatesAsync();
        });
        addTerminalBtn.setOnClickListener(v -> askTerminalName());
        stopTerminalBtn.setOnClickListener(v -> stopSelectedTerminal());
        clearLogsBtn.setOnClickListener(v -> clearLogs());
        githubBtn.setOnClickListener(v -> openGithubRepo());
        followOutputBtn.setOnClickListener(v -> toggleFollowOutput());
        bottomBtn.setOnClickListener(v -> scrollToBottomNow());
        sendInputBtn.setOnClickListener(v -> sendInput());
        if (updateNoticeText != null) updateNoticeText.setOnClickListener(v -> openLatestRelease());
        updateVersionInfoText();

        log("[INFO] Heroku Host ready");
        log("[INFO] Account profile: " + selectedSessionName());
        log("[INFO] Step 1: LINUX, then HEROKU, then START");
        checkForUpdatesAsync();
        refreshProcessUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (followOutput) scrollToBottomSoon();
        refreshProcessUiState();
    }

    @Override
    protected void onDestroy() {
        keepAliveWatchdogRunning = false;
        super.onDestroy();
    }

    private void toggleMenu() {
        if (actionPanel == null) return;
        if (actionPanel.getVisibility() == View.VISIBLE) {
            closeMenu();
            return;
        }
        actionPanel.setVisibility(View.VISIBLE);
        actionPanel.post(() -> {
            actionPanel.setTranslationX(-actionPanel.getWidth());
            actionPanel.animate().translationX(0).setDuration(180).start();
        });
    }

    private void closeMenu() {
        if (actionPanel == null || actionPanel.getVisibility() != View.VISIBLE) return;
        actionPanel.animate()
            .translationX(-actionPanel.getWidth())
            .setDuration(160)
            .withEndAction(() -> actionPanel.setVisibility(View.GONE))
            .start();
    }

    private interface Task { void run() throws Exception; }

    private void runTask(Task task) {
        new Thread(() -> {
            acquireWakeLock();
            try {
                task.run();
            } catch (Exception e) {
                log("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                releaseWakeLock();
            }
        }).start();
    }

    private void requestBackgroundWorkPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                log("[INFO] Please allow background work for long installs");
            }
        } catch (Exception e) {
            log("[WARN] Battery optimization permission screen unavailable: " + e.getMessage());
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HerokuHost:InstallWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60L * 60L * 1000L);
        } catch (Exception e) {
            log("[WARN] WakeLock unavailable: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (hasActiveRuntime() || botSupervisorActive) return;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    private void log(String msg) {
        appendOutput(msg + "\n");
    }

    private void updateStatusLine() {
        if (statusText == null) return;
        runOnUiThread(() -> {
            String linux = isRootfsValid() ? "Linux OK" : "Linux missing";
            String heroku = isHerokuInstalledForSelectedAccount() ? "Heroku OK" : "Heroku missing";
            String bot = currentProcess != null && currentProcess.isAlive() ? "running" : (botSupervisorActive ? "watching" : "stopped");
            String terminal = selectedTerminalName();
            String terminalState = isTerminalAlive(terminal) ? "on" : "off";
            String keepalive = (hasActiveRuntime() || botSupervisorActive) ? "on" : "off";
            statusText.setText(
                "account: " + selectedSessionName()
                    + " | " + linux
                    + " | " + heroku
                    + " | bot: " + bot
                    + " | terms: " + activeTerminalCount()
                    + " | " + terminal + ": " + terminalState
                    + " | keepalive: " + keepalive
            );
        });
    }

    private void refreshProcessUiState() {
        updateStatusLine();
        updateInputHint();
        updateVersionInfoText();
        syncKeepAliveState();
    }

    private void updateVersionInfoText() {
        if (versionInfoText == null) return;
        runOnUiThread(() -> {
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                String versionName = packageInfo.versionName == null ? "?" : packageInfo.versionName;
                int versionCode = packageInfo.versionCode;
                versionInfoText.setText("app version: v" + versionName + " (" + versionCode + ") | last check: " + lastUpdateCheckLabel);
            } catch (Exception e) {
                versionInfoText.setText("app version: unavailable | last check: " + lastUpdateCheckLabel);
            }
        });
    }

    private void updateInputHint() {
        if (inputField == null) return;
        runOnUiThread(() -> {
            String hint;
            Process terminal = terminalProcesses.get(selectedTerminalName());
            if (waitingForInlineBot) {
                hint = "inline bot username, e.g. my_cool_bot";
            } else if (waitingForSessionName) {
                hint = "enter new account profile name";
            } else if (waitingForTerminalName) {
                hint = "enter new terminal session name";
            } else if (terminal != null && terminal.isAlive()) {
                hint = "send input to terminal: " + selectedTerminalName();
            } else if (currentProcess != null && currentProcess.isAlive()) {
                hint = "send input to userbot process";
            } else {
                hint = "send input to active process";
            }
            inputField.setHint(hint);
        });
    }

    private void checkForUpdatesAsync() {
        new Thread(() -> {
            try {
                lastUpdateCheckLabel = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                updateVersionInfoText();
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                int localVersionCode = packageInfo.versionCode;
                String localVersionName = packageInfo.versionName == null ? "?" : packageInfo.versionName;
                String remoteBuildGradle = fetchText(REMOTE_BUILD_GRADLE_URL);
                int remoteVersionCode = parseVersionCode(remoteBuildGradle);
                String remoteVersionName = parseVersionName(remoteBuildGradle);
                if (remoteVersionCode > localVersionCode) {
                    String text = "Update available: local v"
                        + localVersionName + " (" + localVersionCode + ") -> remote v"
                        + remoteVersionName + " (" + remoteVersionCode + "). Tap to open latest release.";
                    showUpdateNotice(text, true);
                    log("[UPDATE] " + text);
                } else {
                    showUpdateNotice("", false);
                    log("[UPDATE] App is up to date: v" + localVersionName + " (" + localVersionCode + ")");
                }
            } catch (Exception e) {
                showUpdateNotice("", false);
                log("[UPDATE] Check failed: " + e.getMessage());
            }
        }, "HerokuHostVersionCheck").start();
    }

    private String fetchText(String url) throws Exception {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream();
             InputStreamReader isr = new InputStreamReader(in);
             BufferedReader reader = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private int parseVersionCode(String text) {
        Matcher matcher = Pattern.compile("versionCode\\s+(\\d+)").matcher(text);
        if (!matcher.find()) throw new IllegalStateException("Remote versionCode not found");
        return Integer.parseInt(matcher.group(1));
    }

    private String parseVersionName(String text) {
        Matcher matcher = Pattern.compile("versionName\\s+\"([^\"]+)\"").matcher(text);
        if (!matcher.find()) return "?";
        return matcher.group(1);
    }

    private void showUpdateNotice(String text, boolean visible) {
        if (updateNoticeText == null) return;
        runOnUiThread(() -> {
            updateNoticeText.setVisibility(visible ? View.VISIBLE : View.GONE);
            updateNoticeText.setText(text);
        });
    }

    private boolean hasActiveRuntime() {
        if (currentProcess != null && currentProcess.isAlive()) return true;
        for (Process process : terminalProcesses.values()) {
            if (process != null && process.isAlive()) return true;
        }
        return false;
    }

    private int activeTerminalCount() {
        int count = 0;
        for (Process process : terminalProcesses.values()) {
            if (process != null && process.isAlive()) count++;
        }
        return count;
    }

    private String activeRuntimeSummary() {
        if (currentProcess != null && currentProcess.isAlive()) {
            return botSupervisorActive ? "Userbot active with watchdog" : "Userbot active";
        }
        int terminalCount = activeTerminalCount();
        if (terminalCount > 0) {
            return terminalCount == 1 ? "1 terminal session active" : terminalCount + " terminal sessions active";
        }
        if (botSupervisorActive) return "Watchdog armed for userbot";
        return "No active runtime";
    }

    private void syncKeepAliveState() {
        boolean shouldStayAlive = hasActiveRuntime() || botSupervisorActive;
        String summary = activeRuntimeSummary();
        String signature = shouldStayAlive + "|" + summary;
        if (signature.equals(lastKeepAliveSignature)) return;
        lastKeepAliveSignature = signature;

        if (shouldStayAlive) {
            acquireWakeLock();
            acquireWifiLock();
            Intent intent = new Intent(this, HostKeepAliveService.class)
                .putExtra(HostKeepAliveService.EXTRA_TITLE, "Heroku Host keepalive")
                .putExtra(HostKeepAliveService.EXTRA_TEXT, summary);
            ContextCompat.startForegroundService(this, intent);
        } else {
            releaseWifiLock();
            releaseWakeLock();
            stopService(new Intent(this, HostKeepAliveService.class));
        }
    }

    private void startKeepAliveWatchdog() {
        if (keepAliveWatchdogThread != null && keepAliveWatchdogThread.isAlive()) return;
        keepAliveWatchdogRunning = true;
        keepAliveWatchdogThread = new Thread(() -> {
            while (keepAliveWatchdogRunning) {
                try {
                    refreshProcessUiState();
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {}
            }
        }, "HerokuHostKeepAliveWatchdog");
        keepAliveWatchdogThread.setDaemon(true);
        keepAliveWatchdogThread.start();
    }

    private void acquireWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) return;
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return;
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HerokuHost:WifiLock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            log("[WARN] WifiLock unavailable: " + e.getMessage());
        }
    }

    private void releaseWifiLock() {
        try {
            if ((hasActiveRuntime() || botSupervisorActive) || wifiLock == null || !wifiLock.isHeld()) return;
            wifiLock.release();
        } catch (Exception ignored) {}
    }

    private void appendOutput(String msg) {
        String clean = filterLogText(msg);
        if (clean.isEmpty()) return;
        runOnUiThread(() -> {
            logConsole.append(clean);
            if (logConsole.length() > MAX_LOG_CHARS) {
                CharSequence text = logConsole.getText();
                logConsole.setText(text.subSequence(text.length() - MAX_LOG_CHARS, text.length()));
            }
            if (followOutput) scrollToBottomSoon();
        });
    }

    private void toggleFollowOutput() {
        followOutput = !followOutput;
        if (followOutputBtn != null) {
            followOutputBtn.setText(followOutput ? "FOLLOW: ON" : "FOLLOW: OFF");
        }
        if (followOutput) scrollToBottomNow();
    }

    private void scrollToBottomSoon() {
        if (scrollScheduled || logScroll == null) return;
        scrollScheduled = true;
        logScroll.postDelayed(() -> {
            scrollScheduled = false;
            scrollToBottomNow();
        }, 80);
    }

    private void scrollToBottomNow() {
        if (logScroll == null) return;
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String filterLogText(String msg) {
        String clean = msg.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
        String[] lines = clean.split("(?<=\\n)", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Requirement already satisfied:")) continue;
            if (trimmed.startsWith("Using cached ")) continue;
            if (trimmed.startsWith("Stored in directory:")) continue;
            if (trimmed.startsWith("Building wheel for ")) continue;
            if (trimmed.startsWith("Created wheel for ")) continue;
            out.append(line);
        }
        return out.toString();
    }

    private File sessionsFile() {
        return new File(baseDir, "sessions.txt");
    }

    private File terminalSessionsFile() {
        return new File(baseDir, "terminal_sessions.txt");
    }

    private void loadSessionProfiles() {
        sessionProfiles.clear();
        sessionProfiles.add("main");
        try {
            File file = sessionsFile();
            if (file.exists()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String profile = sanitizeSessionName(line);
                        if (!profile.isEmpty() && !sessionProfiles.contains(profile)) sessionProfiles.add(profile);
                    }
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(sessionProfiles.subList(1, sessionProfiles.size()));
    }

    private void setupSessionMenu() {
        sessionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sessionProfiles);
        sessionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sessionSpinner.setAdapter(sessionAdapter);
        String saved = loadSelectedSessionName();
        int index = sessionProfiles.indexOf(saved);
        if (index >= 0) sessionSpinner.setSelection(index);
        sessionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try { saveSelectedSessionName(sessionProfiles.get(position)); } catch (Exception ignored) {}
                refreshProcessUiState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadTerminalProfiles() {
        terminalProfiles.clear();
        terminalProfiles.add("term1");
        try {
            File file = terminalSessionsFile();
            if (file.exists()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String profile = sanitizeSessionName(line);
                        if (!profile.isEmpty() && !terminalProfiles.contains(profile)) terminalProfiles.add(profile);
                    }
                }
            }
        } catch (Exception ignored) {}
        Collections.sort(terminalProfiles.subList(1, terminalProfiles.size()));
    }

    private void setupTerminalMenu() {
        terminalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, terminalProfiles);
        terminalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        terminalSpinner.setAdapter(terminalAdapter);
        String saved = loadSelectedTerminalName();
        int index = terminalProfiles.indexOf(saved);
        if (index >= 0) terminalSpinner.setSelection(index);
        terminalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try { saveSelectedTerminalName(terminalProfiles.get(position)); } catch (Exception ignored) {}
                refreshProcessUiState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private File selectedSessionFile() {
        return new File(baseDir, "selected_session.txt");
    }

    private File selectedTerminalFile() {
        return new File(baseDir, "selected_terminal.txt");
    }

    private String loadSelectedSessionName() {
        try {
            File file = selectedSessionFile();
            if (!file.exists()) return "main";
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = in.read(data);
                if (read <= 0) return "main";
            }
            String selected = sanitizeSessionName(new String(data));
            return selected.isEmpty() ? "main" : selected;
        } catch (Exception ignored) {
            return "main";
        }
    }

    private void saveSelectedSessionName(String profile) throws Exception {
        writeFile(selectedSessionFile(), sanitizeSessionName(profile) + "\n");
    }

    private String loadSelectedTerminalName() {
        try {
            File file = selectedTerminalFile();
            if (!file.exists()) return "term1";
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = in.read(data);
                if (read <= 0) return "term1";
            }
            String selected = sanitizeSessionName(new String(data));
            return selected.isEmpty() ? "term1" : selected;
        } catch (Exception ignored) {
            return "term1";
        }
    }

    private void saveSelectedTerminalName(String profile) throws Exception {
        writeFile(selectedTerminalFile(), sanitizeSessionName(profile) + "\n");
    }

    private void saveSessionProfiles() throws Exception {
        StringBuilder data = new StringBuilder();
        for (String profile : sessionProfiles) data.append(profile).append('\n');
        writeFile(sessionsFile(), data.toString());
    }

    private void saveTerminalProfiles() throws Exception {
        StringBuilder data = new StringBuilder();
        for (String profile : terminalProfiles) data.append(profile).append('\n');
        writeFile(terminalSessionsFile(), data.toString());
    }

    private String selectedSessionName() {
        Object selected = sessionSpinner == null ? null : sessionSpinner.getSelectedItem();
        String profile = sanitizeSessionName(selected == null ? "main" : selected.toString());
        return profile.isEmpty() ? "main" : profile;
    }

    private String selectedTerminalName() {
        Object selected = terminalSpinner == null ? null : terminalSpinner.getSelectedItem();
        String profile = sanitizeSessionName(selected == null ? "term1" : selected.toString());
        return profile.isEmpty() ? "term1" : profile;
    }

    private boolean isTerminalAlive(String name) {
        Process process = terminalProcesses.get(name);
        return process != null && process.isAlive();
    }

    private String sanitizeSessionName(String input) {
        if (input == null) return "";
        String name = input.trim().toLowerCase(java.util.Locale.US).replace("@", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            if (ok) out.append(ch);
        }
        return out.toString();
    }

    private String herokuDirName() {
        String profile = selectedSessionName();
        return profile.equals("main") ? "Heroku" : "Heroku-" + profile;
    }

    private String herokuPath() {
        return "/root/" + herokuDirName();
    }

    private File herokuRootfsDir() {
        return new File(rootfsDir, "root/" + herokuDirName());
    }

    private boolean isHerokuInstalledForSelectedAccount() {
        File dir = herokuRootfsDir();
        return new File(dir, "heroku").exists()
            && fileExistsOrSymlink(new File(dir, ".venv/bin/python"))
                || (new File(dir, "heroku").exists() && fileExistsOrSymlink(new File(dir, ".venv/bin/python3")));
    }

    private boolean fileExistsOrSymlink(File file) {
        try {
            return file.exists() || Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            return file.exists();
        }
    }

    private void askSessionName() {
        waitingForSessionName = true;
        runOnUiThread(() -> {
            inputField.setText("");
            inputField.setHint("session name, e.g. second");
        });
        refreshProcessUiState();
        log("[SETUP] Type new account profile name and press SEND.");
    }

    private void askTerminalName() {
        waitingForTerminalName = true;
        runOnUiThread(() -> {
            inputField.setText("");
            inputField.setHint("terminal name, e.g. deps");
        });
        refreshProcessUiState();
        log("[SETUP] Type new terminal session name and press SEND.");
    }

    private void saveNewSession(String rawName) {
        String profile = sanitizeSessionName(rawName);
        if (profile.isEmpty()) {
            log("[ERROR] Invalid session name. Use a-z, 0-9, _ or -.");
            askSessionName();
            return;
        }
        try {
            if (!sessionProfiles.contains(profile)) {
                sessionProfiles.add(profile);
                Collections.sort(sessionProfiles.subList(1, sessionProfiles.size()));
                saveSessionProfiles();
                setupSessionMenu();
            }
            sessionSpinner.setSelection(sessionProfiles.indexOf(profile));
            saveSelectedSessionName(profile);
            waitingForSessionName = false;
            log("[OK] Account profile selected: " + profile);
            log("[INFO] For another Telegram account press HEROKU, then START and login with its phone.");
            refreshProcessUiState();
        } catch (Exception e) {
            log("[ERROR] Failed to save session: " + e.getMessage());
        }
    }

    private void saveNewTerminal(String rawName) {
        String profile = sanitizeSessionName(rawName);
        if (profile.isEmpty()) {
            log("[ERROR] Invalid terminal name. Use a-z, 0-9, _ or -.");
            askTerminalName();
            return;
        }
        try {
            if (!terminalProfiles.contains(profile)) {
                terminalProfiles.add(profile);
                Collections.sort(terminalProfiles.subList(1, terminalProfiles.size()));
                saveTerminalProfiles();
                setupTerminalMenu();
            }
            terminalSpinner.setSelection(terminalProfiles.indexOf(profile));
            saveSelectedTerminalName(profile);
            waitingForTerminalName = false;
            log("[OK] Terminal session selected: " + profile);
            log("[INFO] Press OPEN SELECTED TERMINAL to start it.");
            refreshProcessUiState();
        } catch (Exception e) {
            log("[ERROR] Failed to save terminal session: " + e.getMessage());
        }
    }

    private void startHostMetricsWriter() {
        if (metricsThread != null && metricsThread.isAlive()) return;
        metricsThread = new Thread(() -> {
            while (true) {
                try {
                    writeHostInfo();
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored2) {}
                }
            }
        });
        metricsThread.setDaemon(true);
        metricsThread.start();
    }

    private void writeHostInfo() throws Exception {
        supportDir.mkdirs();
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        long total = 0;
        long avail = 0;
        if (am != null) {
            am.getMemoryInfo(mi);
            total = mi.totalMem;
            avail = mi.availMem;
        }
        long usedMb = Math.max(total - avail, 0) / 1024 / 1024;
        long totalMb = Math.max(total, 0) / 1024 / 1024;
        double cpu = sampleCpuPercent();
        String cpuPercent = cpu >= 0 ? String.format(java.util.Locale.US, "%.1f%%", cpu) : "N/A";
        int cores = Runtime.getRuntime().availableProcessors();
        String json = "{"
            + "\"host\":\"herokuapk\"," 
            + "\"cpu_usage\":\"" + cpuPercent + "\"," 
            + "\"ram_usage\":\"" + usedMb + " MB\"," 
            + "\"cpu\":\"" + cores + " (" + cores + ") core(-s); " + cpuPercent + " total\"," 
            + "\"ram_total\":\"" + totalMb + " MB\""
            + "}";
        writeFile(new File(supportDir, "host_info.json"), json);
    }

    private double sampleCpuPercent() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return lastCpuPercent;
            String[] parts = line.trim().split("\\s+");
            long user = Long.parseLong(parts[1]);
            long nice = Long.parseLong(parts[2]);
            long system = Long.parseLong(parts[3]);
            long idle = Long.parseLong(parts[4]);
            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
            long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
            long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;
            long idleAll = idle + iowait;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            if (lastCpuTotal == 0) {
                lastCpuTotal = total;
                lastCpuIdle = idleAll;
                return lastCpuPercent;
            }
            long totalDelta = total - lastCpuTotal;
            long idleDelta = idleAll - lastCpuIdle;
            lastCpuTotal = total;
            lastCpuIdle = idleAll;
            if (totalDelta <= 0) return lastCpuPercent;
            lastCpuPercent = Math.max(0, Math.min(100, (totalDelta - idleDelta) * 100.0 / totalDelta));
            return lastCpuPercent;
        } catch (Exception ignored) {
            return sampleTopCpuPercent();
        }
    }

    private double sampleTopCpuPercent() {
        try {
            Process process = new ProcessBuilder("/system/bin/top", "-b", "-n", "1").redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String lower = line.toLowerCase(java.util.Locale.US);
                    if (lower.contains("%cpu") || lower.startsWith("cpu") || lower.contains(" cpu ")) {
                        double parsed = parseFirstPercentNumber(line);
                        if (parsed >= 0) {
                            lastCpuPercent = Math.max(0, Math.min(100, parsed));
                            return lastCpuPercent;
                        }
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {}
        return lastCpuPercent;
    }

    private double parseFirstPercentNumber(String line) {
        ArrayList<Double> values = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '%') continue;
            int start = i - 1;
            while (start >= 0) {
                char ch = line.charAt(start);
                if ((ch >= '0' && ch <= '9') || ch == '.') {
                    start--;
                } else {
                    break;
                }
            }
            if (start + 1 < i) {
                try { values.add(Double.parseDouble(line.substring(start + 1, i))); } catch (Exception ignored) {}
            }
        }
        if (values.isEmpty()) return -1;
        if (values.get(0) > 100 && values.size() > 2) return values.get(1) + values.get(2);
        return values.get(0);
    }

    private String assetArch() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.contains("arm64")) return "arm64-v8a";
        if (abi.contains("armeabi")) return "armeabi-v7a";
        if (abi.contains("x86_64")) return "x86_64";
        if (abi.contains("x86")) return "x86";
        return abi;
    }

    private String ubuntuUrl() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.contains("arm64")) return UBUNTU_BASE + "ubuntu-base-24.04.4-base-arm64.tar.gz";
        if (abi.contains("armeabi")) return UBUNTU_BASE + "ubuntu-base-24.04.4-base-armhf.tar.gz";
        if (abi.contains("x86_64")) return UBUNTU_BASE + "ubuntu-base-24.04.4-base-amd64.tar.gz";
        throw new IllegalStateException("Unsupported ABI for Ubuntu rootfs: " + abi);
    }

    private void installLinux() throws Exception {
        log("[INFO] Installing mini UserLAnd");
        log("[INFO] ABI: " + Build.SUPPORTED_ABIS[0] + " -> " + assetArch());
        baseDir.mkdirs();
        supportDir.mkdirs();

        installSupportAssets();

        if (rootfsDir.exists() && !isRootfsValid()) {
            log("[WARN] Existing rootfs is incomplete. Reinstalling rootfs...");
            deleteRecursive(rootfsDir);
        }

        if (!new File(rootfsDir, "bin/sh").exists()) {
            File rootfs = new File(baseDir, "ubuntu-base.tar.gz");
            download(ubuntuUrl(), rootfs);
            rootfsDir.mkdirs();
            extractTarGz(rootfs, rootfsDir);
            setupRootfs();
            repairRootfs();
            if (!isRootfsValid()) throw new IllegalStateException("rootfs validation failed");
        } else {
            log("[INFO] rootfs already installed");
            setupRootfs();
            repairRootfs();
        }

        if (!testProotRuntime()) {
            log("[WARN] runtime failed. Reinstalling rootfs...");
            deleteRecursive(rootfsDir);
            File rootfs = new File(baseDir, "ubuntu-base.tar.gz");
            if (!rootfs.exists()) download(ubuntuUrl(), rootfs);
            rootfsDir.mkdirs();
            extractTarGz(rootfs, rootfsDir);
            setupRootfs();
            repairRootfs();
            if (!testProotRuntime()) throw new IllegalStateException("proot runtime test failed after reinstall");
        }

        log("[DONE] Linux installed. Now press INSTALL HEROKU");
    }

    private void installSupportAssets() throws Exception {
        File marker = new File(supportDir, "execInProot.sh");
        if (marker.exists()) {
            log("[INFO] UserLAnd support assets already installed");
            normalizeSupportAssets();
            return;
        }

        log("[INFO] Installing embedded UserLAnd support assets...");
        copyAssetDir("userland/" + assetArch(), supportDir);
        File[] files = supportDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.setReadable(true, false);
                file.setWritable(true, false);
                file.setExecutable(true, false);
            }
        }
        normalizeSupportAssets();
        log("[OK] UserLAnd support assets installed");
    }

    private void normalizeSupportAssets() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copySupportFile("proot.a10", "proot");
            copySupportFile("loader.a10", "loader");
            copySupportFile("loader32.a10", "loader32");
            copySupportFile("libtalloc.so.2.a10", "libtalloc.so.2");
        }

        File[] files = supportDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.setReadable(true, false);
                file.setWritable(true, false);
                file.setExecutable(true, false);
            }
        }
    }

    private void copySupportFile(String sourceName, String destName) throws Exception {
        File source = new File(supportDir, sourceName);
        File dest = new File(supportDir, destName);
        if (!source.exists()) return;
        if (dest.exists()) dest.delete();
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        dest.setReadable(true, false);
        dest.setWritable(true, false);
        dest.setExecutable(true, false);
    }

    private void copyAssetDir(String assetPath, File dest) throws Exception {
        AssetManager manager = getAssets();
        String[] entries = manager.list(assetPath);
        if (entries == null || entries.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = manager.open(assetPath); FileOutputStream out = new FileOutputStream(dest)) {
                copy(in, out);
            }
            return;
        }

        dest.mkdirs();
        for (String entry : entries) {
            copyAssetDir(assetPath + "/" + entry, new File(dest, entry));
        }
    }

    private void download(String url, File out) throws Exception {
        log("[DOWNLOAD] " + url);
        try (InputStream in = new URL(url).openStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[1024 * 64];
            long total = 0;
            int read;
            long lastLog = 0;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                total += read;
                if (total - lastLog > 1024 * 1024 * 5) {
                    lastLog = total;
                    log("[DOWNLOAD] " + (total / 1024 / 1024) + " MB");
                }
            }
        }
    }

    private void extractTarGz(File tarGz, File dest) throws Exception {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new java.io.BufferedInputStream(new GZIPInputStream(new FileInputStream(tarGz))))) {
            TarArchiveEntry entry;
            byte[] buf = new byte[1024 * 64];
            int count = 0;
            while ((entry = tar.getNextTarEntry()) != null) {
                File out = new File(dest, entry.getName());
                String canonicalDest = dest.getCanonicalPath();
                String canonicalOut = out.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalDest)) continue;

                if (entry.isDirectory()) {
                    out.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    out.getParentFile().mkdirs();
                    try { Os.symlink(entry.getLinkName(), out.getAbsolutePath()); } catch (Exception ignored) {}
                } else if (entry.isLink()) {
                    out.getParentFile().mkdirs();
                    File target = new File(dest, entry.getLinkName());
                    if (target.exists()) {
                        try { Os.link(target.getAbsolutePath(), out.getAbsolutePath()); }
                        catch (Exception ignored) { try { Os.symlink(entry.getLinkName(), out.getAbsolutePath()); } catch (Exception ignored2) {} }
                    } else {
                        try { Os.symlink(entry.getLinkName(), out.getAbsolutePath()); } catch (Exception ignored) {}
                    }
                } else if (entry.isFile()) {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int len;
                        while ((len = tar.read(buf)) != -1) fos.write(buf, 0, len);
                    }
                    if ((entry.getMode() & 0100) != 0) out.setExecutable(true, false);
                }
                count++;
                if (count % 1200 == 0) log("[EXTRACT] " + count + " entries");
            }
        }
    }

    private void setupRootfs() throws Exception {
        new File(rootfsDir, "dev").mkdirs();
        new File(rootfsDir, "proc").mkdirs();
        new File(rootfsDir, "sys").mkdirs();
        new File(rootfsDir, "tmp").mkdirs();
        File support = new File(rootfsDir, "support");
        support.mkdirs();
        new File(support, "common").mkdirs();

        writeFile(new File(rootfsDir, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        writeFile(new File(rootfsDir, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        writeFile(new File(rootfsDir, "etc/hostname"), "localhost\n");
        writeFile(new File(support, ".proot_version"), ".a10\n");
        writeFile(new File(support, "ld.so.preload"), "");
        writeFile(new File(support, "nosudo"), "#!/bin/sh\nexec \"$@\"\n");
        writeFile(new File(support, "userland_profile.sh"), "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        writeFile(new File(support, "version"), "Linux version 6.1.0 (userland@android) #1 SMP\n");
        writeHostInfo();
        new File(support, "nosudo").setExecutable(true, false);

        writeExecutableScript(new File(rootfsDir, "usr/local/bin/id"), "#!/bin/sh\n/support/common/busybox id \"$@\"\n");
        writeExecutableScript(new File(rootfsDir, "usr/bin/id"), "#!/bin/sh\n/support/common/busybox id \"$@\"\n");
        writeExecutableScript(new File(rootfsDir, "bin/id"), "#!/bin/sh\n/support/common/busybox id \"$@\"\n");

        copyAllSupportAssetsToRootfsSupport(support);
        copySupportToRootfsSupport("stat4");
        copySupportToRootfsSupport("stat8");
        copySupportToRootfsSupport("uptime");
    }

    private void copyAllSupportAssetsToRootfsSupport(File rootfsSupport) throws Exception {
        File[] files = supportDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            File dst = new File(rootfsSupport, file.getName());
            if (dst.exists()) continue;
            try (FileInputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            dst.setReadable(true, false);
            dst.setWritable(true, false);
            dst.setExecutable(true, false);
        }
    }

    private void copySupportToRootfsSupport(String name) throws Exception {
        File src = new File(supportDir, name);
        File dst = new File(rootfsDir, "support/" + name);
        if (!src.exists() || dst.exists()) return;
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) { copy(in, out); }
    }

    private void writeFile(File file, String content) throws Exception {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(content.getBytes()); }
    }

    private void writeExecutableScript(File file, String content) throws Exception {
        writeFile(file, content);
        file.setReadable(true, false);
        file.setWritable(true, false);
        file.setExecutable(true, false);
    }

    private void repairRootfs() throws Exception {
        log("[INFO] Repairing rootfs runtime files...");
        File[] shellCandidates = new File[] {
            new File(rootfsDir, "usr/bin/dash"), new File(rootfsDir, "usr/bin/bash"),
            new File(rootfsDir, "bin/dash"), new File(rootfsDir, "bin/bash"),
            new File(rootfsDir, "usr/bin/sh"), new File(rootfsDir, "bin/sh")
        };
        copyFirstExisting(shellCandidates, new File(rootfsDir, "usr/bin/sh"), true, true);
        copyFirstExisting(shellCandidates, new File(rootfsDir, "bin/sh"), true, true);

        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.contains("arm64")) {
            copyFirstExisting(new File[] { new File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"), new File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"), new File(rootfsDir, "lib/ld-linux-aarch64.so.1") }, new File(rootfsDir, "lib/ld-linux-aarch64.so.1"), true, true);
        } else if (abi.contains("armeabi")) {
            copyFirstExisting(new File[] { new File(rootfsDir, "usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3"), new File(rootfsDir, "lib/arm-linux-gnueabihf/ld-linux-armhf.so.3"), new File(rootfsDir, "lib/ld-linux-armhf.so.3") }, new File(rootfsDir, "lib/ld-linux-armhf.so.3"), true, true);
        } else if (abi.contains("x86_64")) {
            copyFirstExisting(new File[] { new File(rootfsDir, "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"), new File(rootfsDir, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"), new File(rootfsDir, "lib64/ld-linux-x86-64.so.2") }, new File(rootfsDir, "lib64/ld-linux-x86-64.so.2"), true, true);
        }
    }

    private void copyFirstExisting(File[] candidates, File destination, boolean executable, boolean overwrite) throws Exception {
        if (destination.exists() && !overwrite) return;
        for (File candidate : candidates) {
            if (candidate.equals(destination)) continue;
            if (candidate.exists() && candidate.isFile()) {
                destination.getParentFile().mkdirs();
                if (destination.exists() || Files.isSymbolicLink(destination.toPath())) destination.delete();
                try (FileInputStream in = new FileInputStream(candidate); FileOutputStream out = new FileOutputStream(destination)) { copy(in, out); }
                destination.setReadable(true, false);
                if (executable) destination.setExecutable(true, false);
                return;
            }
        }
        throw new IllegalStateException("No candidate found for " + destination.getAbsolutePath());
    }

    private boolean isRootfsValid() {
        return new File(rootfsDir, "bin/sh").exists() && new File(rootfsDir, "usr/bin/env").exists();
    }

    private String[] prootCommand(String command) {
        ArrayList<String> args = new ArrayList<>();
        args.add(supportDir.getAbsolutePath() + "/proot");
        args.add("-r");
        args.add(rootfsDir.getAbsolutePath());
        args.add("-v");
        args.add("-1");
        args.add("-p");
        args.add("--sysvipc");
        args.add("-H");
        args.add("-0");
        args.add("-l");
        args.add("-L");
        args.add("-b");
        args.add("/sys");
        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        args.add("-b");
        args.add("/data");
        args.add("-b");
        args.add("/mnt");
        args.add("-b");
        args.add("/proc/mounts:/etc/mtab");
        args.add("-b");
        args.add("/:/host-rootfs");
        addProcFakeBinds(args);
        args.add("-b");
        args.add(new File(rootfsDir, "support").getAbsolutePath() + ":/support");
        args.add("-b");
        args.add(new File(rootfsDir, "support/nosudo").getAbsolutePath() + ":/usr/local/bin/sudo");
        args.add("-b");
        args.add(new File(rootfsDir, "support/userland_profile.sh").getAbsolutePath() + ":/etc/profile.d/userland_profile.sh");
        args.add("-b");
        args.add(new File(rootfsDir, "support/ld.so.preload").getAbsolutePath() + ":/etc/ld.so.preload");
        args.add("-b");
        args.add(supportDir.getAbsolutePath() + ":/support/common");
        args.add("-w");
        args.add("/root");
        args.add(shellPath());
        args.add("-c");
        args.add(command);
        return args.toArray(new String[0]);
    }

    private void addProcFakeBinds(ArrayList<String> args) {
        addBindIfExists(args, new File(rootfsDir, "support/stat8"), "/proc/stat");
        addBindIfExists(args, new File(rootfsDir, "support/uptime"), "/proc/uptime");
        addBindIfExists(args, new File(rootfsDir, "support/version"), "/proc/version");
    }

    private void addBindIfExists(ArrayList<String> args, File source, String target) {
        if (!source.exists()) return;
        args.add("-b");
        args.add(source.getAbsolutePath() + ":" + target);
    }

    private String shellPath() {
        if (new File(rootfsDir, "bin/sh").exists()) return "/bin/sh";
        if (new File(rootfsDir, "usr/bin/sh").exists()) return "/usr/bin/sh";
        if (new File(rootfsDir, "bin/bash").exists()) return "/bin/bash";
        return "/usr/bin/bash";
    }

    private Map<String, String> prootEnv() {
        Map<String, String> env = new HashMap<>();
        File statFile = new File(rootfsDir, "support/stat8");
        File uptimeFile = new File(rootfsDir, "support/uptime");
        File versionFile = new File(rootfsDir, "support/version");
        String procBindings = "";
        if (statFile.exists()) procBindings += " -b " + statFile.getAbsolutePath() + ":/proc/stat";
        if (uptimeFile.exists()) procBindings += " -b " + uptimeFile.getAbsolutePath() + ":/proc/uptime";
        if (versionFile.exists()) procBindings += " -b " + versionFile.getAbsolutePath() + ":/proc/version";

        env.put("LD_LIBRARY_PATH", supportDir.getAbsolutePath());
        env.put("LIB_PATH", supportDir.getAbsolutePath());
        env.put("ROOT_PATH", baseDir.getAbsolutePath());
        env.put("ROOTFS_PATH", rootfsDir.getAbsolutePath());
        env.put("PROOT_DEBUG_LEVEL", "-1");
        env.put("PROOT_TMP_DIR", new File(rootfsDir, "support").getAbsolutePath());
        env.put("PROOT_LOADER", new File(supportDir, "loader").getAbsolutePath());
        env.put("PROOT_LOADER_32", new File(supportDir, "loader32").getAbsolutePath());
        env.put("EXTRA_BINDINGS", "-b " + getExternalFilesDir(null).getAbsolutePath() + ":/storage/internal -b " + supportDir.getAbsolutePath() + "/host_info.json:/support/common/host_info.json" + procBindings);
        env.put("OS_VERSION", System.getProperty("os.version", "4.0.0"));
        return env;
    }

    private boolean testProotRuntime() {
        if (!new File(supportDir, "busybox").exists() || !new File(supportDir, "execInProot.sh").exists()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(prootCommand("echo PROOT_OK"));
            pb.directory(baseDir);
            pb.environment().putAll(prootEnv());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append('\n');
            }
            int code = p.waitFor();
            if (code != 0) {
                log("[RUNTIME TEST FAILED] " + out.toString().trim());
                return false;
            }
            return out.toString().contains("PROOT_OK");
        } catch (Exception e) {
            log("[RUNTIME TEST ERROR] " + e.getMessage());
            return false;
        }
    }

    private void installHeroku() {
        String dirName = herokuDirName();
        String path = herokuPath();
        startProcess("export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color DEBIAN_FRONTEND=noninteractive && " +
            "dpkg --remove --force-remove-reinstreq --force-depends dbus libpam-systemd systemd-resolved networkd-dispatcher dbus-user-session dconf-service dconf-gsettings-backend libgtk-3-common gsettings-desktop-schemas libgtk-3-bin libgtk-3-0t64 at-spi2-core libdecor-0-plugin-1-gtk 2>/dev/null || true && " +
            "apt update && apt install -y --no-install-recommends ca-certificates coreutils git python3 python3-pip python3-venv build-essential libcairo2 libmagic1 openssl && " +
            "cd /root && if [ ! -d " + dirName + " ]; then git clone https://github.com/coddrago/Heroku " + dirName + "; fi && " +
            "cd " + path + " && python3 -m venv .venv && " +
            ".venv/bin/python -m pip install --upgrade pip wheel setuptools && " +
            ".venv/bin/python -m pip install -r requirements.txt && " +
            ".venv/bin/python -c \"import hashlib; open('.requirements_hash','w').write(hashlib.sha256(open('requirements.txt','rb').read()).hexdigest())\"", false, true, false, "INSTALL HEROKU");
    }

    private void runDiagnostics() {
        closeMenu();
        runTask(() -> {
            refreshProcessUiState();
            log("[DIAG] Account: " + selectedSessionName());
            log("[DIAG] Heroku path: " + herokuPath());
            log("[DIAG] Android ABI: " + Build.SUPPORTED_ABIS[0]);
            log("[DIAG] Linux rootfs: " + (isRootfsValid() ? "OK" : "missing/broken"));
            log("[DIAG] Support assets: " + (new File(supportDir, "proot").exists() ? "OK" : "missing"));
            log("[DIAG] Heroku repo: " + (new File(herokuRootfsDir(), "heroku").exists() ? "OK" : "missing"));
            log("[DIAG] venv python: " + (fileExistsOrSymlink(new File(herokuRootfsDir(), ".venv/bin/python")) ? "OK" : "missing"));
            log("[DIAG] inline bot: " + (isInlineBotUsernameValid(getInlineBotUsername()) ? "@" + getInlineBotUsername() : "not set"));
            log("[DIAG] bot process: " + ((currentProcess != null && currentProcess.isAlive()) ? "running" : "stopped"));
            try {
                writeHostInfo();
                log("[DIAG] host info: updated");
            } catch (Exception e) {
                log("[DIAG] host info error: " + e.getMessage());
            }
        });
    }

    private void repairRuntime() throws Exception {
        closeMenu();
        log("[REPAIR] Repairing Linux runtime files...");
        if (!new File(supportDir, "execInProot.sh").exists()) installSupportAssets();
        setupRootfs();
        repairRootfs();
        if (testProotRuntime()) {
            log("[REPAIR] Runtime OK");
        } else {
            log("[REPAIR] Runtime still broken. Press DOWNLOAD LINUX if needed.");
        }
        refreshProcessUiState();
    }

    private void updateHeroku() {
        closeMenu();
        String path = herokuPath();
        if (!isHerokuInstalledForSelectedAccount()) {
            log("[ERROR] Heroku is not installed for account profile: " + selectedSessionName());
            return;
        }
        startProcess("export HOME=/root PATH=" + path + "/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color PYTHONUNBUFFERED=1 && cd " + path + " && " +
            "git pull --ff-only || git pull && " +
            ".venv/bin/python -m pip install -r requirements.txt && " +
            "rm -f " + PATCH_MARKER + " && " +
            herokuApkPatchCommand(), false, false, false, "UPDATE HEROKU");
    }

    private void reapplyPatches() {
        closeMenu();
        String path = herokuPath();
        if (!isHerokuInstalledForSelectedAccount()) {
            log("[ERROR] Heroku is not installed for account profile: " + selectedSessionName());
            return;
        }
        startProcess("export HOME=/root PATH=" + path + "/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color PYTHONUNBUFFERED=1 && cd " + path + " && " +
            "rm -f " + PATCH_MARKER + " && " + herokuApkPatchCommand(), false, false, false, "REAPPLY PATCHES");
    }

    private void startInteractiveBot() {
        closeMenu();
        String inlineBot = getInlineBotUsername();
        if (!isInlineBotUsernameValid(inlineBot)) {
            askInlineBotUsername();
            return;
        }

        if (!isHerokuInstalledForSelectedAccount()) {
            log("[ERROR] Heroku is not installed for account profile: " + selectedSessionName());
            log("[INFO] Press INSTALL HEROKU first for this account profile.");
            return;
        }

        if (botSupervisorActive || (currentProcess != null && currentProcess.isAlive())) {
            log("[INFO] Userbot is already running. Press STOP PROCESS first if you want to restart it.");
            return;
        }

        manualStop = false;
        botAutoRestartEnabled = true;
        botSupervisorActive = true;
        String path = herokuPath();
        startProcess("export HOME=/root PATH=" + path + "/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color PYTHONUNBUFFERED=1 HEROKUAPK=1 HEROKU_CUSTOM_INLINE_BOT='" + inlineBot + "' && cd " + path + " && " +
            herokuApkPatchCommand() + " && " +
            ".venv/bin/python -u -m heroku --no-web --root", true, false, true, "START BOT", true);
    }

    private String cleanupStaleHerokuCommand() {
        return "if [ -x /usr/bin/python3 ]; then /usr/bin/python3 - <<'PY'\n" +
            "import os, signal, time\n" +
            "me = os.getpid()\n" +
            "parent = os.getppid()\n" +
            "targets = []\n" +
            "for name in os.listdir('/proc'):\n" +
            "    if not name.isdigit():\n" +
            "        continue\n" +
            "    pid = int(name)\n" +
            "    if pid in (me, parent):\n" +
            "        continue\n" +
            "    try:\n" +
            "        comm = open(f'/proc/{pid}/comm', 'r').read().strip().lower()\n" +
            "    except Exception:\n" +
            "        continue\n" +
            "    if 'python' not in comm:\n" +
            "        continue\n" +
            "    try:\n" +
            "        cmd = open(f'/proc/{pid}/cmdline', 'rb').read().replace(b'\\0', b' ').decode('utf-8', 'ignore')\n" +
            "    except Exception:\n" +
            "        continue\n" +
            "    low = cmd.lower()\n" +
            "    if 'python' in low and (' -m heroku' in low or 'heroku.__main__' in low):\n" +
            "        targets.append(pid)\n" +
            "for sig in (signal.SIGTERM, signal.SIGKILL):\n" +
            "    for pid in targets:\n" +
            "        try:\n" +
            "            os.kill(pid, sig)\n" +
            "        except Exception:\n" +
            "            pass\n" +
            "    time.sleep(0.4)\n" +
            "PY\n" +
            "fi; true";
    }

    private void startTerminalSession() {
        closeMenu();
        followOutput = true;
        if (followOutputBtn != null) followOutputBtn.setText("FOLLOW: ON");
        manualStop = false;
        botAutoRestartEnabled = false;
        String path = herokuPath();
        String terminalName = selectedTerminalName();
        Process existing = terminalProcesses.get(terminalName);
        if (existing != null && existing.isAlive()) {
            log("[TERMINAL:" + terminalName + "] already running. Input will be sent there.");
            refreshProcessUiState();
            return;
        }
        startTerminalProcess(
            terminalName,
            "export HOME=/root PATH=" + path + "/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color PYTHONUNBUFFERED=1 && " +
                "cd " + path + " 2>/dev/null || cd /root && " +
                "echo '[TERMINAL:" + terminalName + "] Type commands below and press SEND' && /bin/sh -i"
        );
    }

    private void startTerminalProcess(String terminalName, String command) {
        runTask(() -> {
            if (!new File(supportDir, "execInProot.sh").exists() || !new File(rootfsDir, "bin/sh").exists()) {
                log("[ERROR] Linux is not installed. Press LINUX first.");
                return;
            }
            setupRootfs();
            repairRootfs();
            log("[TERMINAL:" + terminalName + "] starting");
            ProcessBuilder pb = new ProcessBuilder(prootCommand(command));
            pb.directory(baseDir);
            pb.environment().putAll(prootEnv());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            terminalProcesses.put(terminalName, process);
            refreshProcessUiState();
            pumpOutput(process.getInputStream());
            int code = process.waitFor();
            if (terminalProcesses.get(terminalName) == process) terminalProcesses.remove(terminalName);
            log("[TERMINAL:" + terminalName + "] exited code " + code);
            refreshProcessUiState();
        });
    }

    private void stopSelectedTerminal() {
        closeMenu();
        String terminalName = selectedTerminalName();
        Process process = terminalProcesses.get(terminalName);
        if (process == null || !process.isAlive()) {
            log("[TERMINAL:" + terminalName + "] not running");
            refreshProcessUiState();
            return;
        }
        process.destroy();
        try { process.destroyForcibly(); } catch (Exception ignored) {}
        terminalProcesses.remove(terminalName);
        log("[TERMINAL:" + terminalName + "] stopped");
        refreshProcessUiState();
    }

    private String herokuApkPatchCommand() {
        return "if [ ! -f " + PATCH_MARKER + " ]; then " +
            hotfixInlineTokenCommand() + " >hotfix_inline.log 2>&1 && " +
            hotfixInfoCommand() + " >hotfix_info.log 2>&1 && " +
            hotfixMetricsCommand() + " >hotfix_metrics.log 2>&1 && " +
            hotfixRestartCommand() + " >hotfix_restart.log 2>&1 && " +
            hotfixRestoreHelpPingCommand() + " >hotfix_restore_help_ping.log 2>&1 && " +
            hotfixDeveloperCommand() + " >hotfix_developer.log 2>&1 && " +
            "touch " + PATCH_MARKER + "; fi";
    }

    private String hotfixRestoreHelpPingCommand() {
        return "(git checkout -- heroku/modules/help.py heroku/modules/test.py 2>/dev/null || true)";
    }

    private String hotfixDeveloperCommand() {
        return "cat > hotfix_developer.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "init = Path('heroku/__init__.py')\n" +
            "if init.exists():\n" +
            "    s = init.read_text()\n" +
            "    s = s.replace('__ForkAuthor__ = \"Codrago\"', '__ForkAuthor__ = \"@ziwupa\"')\n" +
            "    s = s.replace('__maintainer__ = \"developer\"', '__maintainer__ = \"@ziwupa\"')\n" +
            "    init.write_text(s)\n" +
            "for path in Path('heroku/langpacks').glob('*.yml'):\n" +
            "    lines = []\n" +
            "    for line in path.read_text().splitlines():\n" +
            "        if line.lstrip().startswith('ratko:'):\n" +
            "            indent = line[:len(line) - len(line.lstrip())]\n" +
            "            line = indent + 'ratko: \"{} <b>{}.{}.{}</b>\\\\n\\\\n<tg-emoji emoji-id=5310296284874159738>⚪️</tg-emoji> <b>Developer: <a href=\\\"https://t.me/ziwupa\\\">@ziwupa</a></b>\"'\n" +
            "        lines.append(line)\n" +
            "    path.write_text('\\n'.join(lines) + '\\n')\n" +
            "settings = Path('heroku/modules/settings.py')\n" +
            "s = settings.read_text()\n" +
            "if 'async def herokucmd' not in s:\n" +
            "    marker = '\\n    @loader.command()\\n    async def blacklist'\n" +
            "    insert = '\\n    @loader.command()\\n    async def herokucmd(self, message: Message):\\n        await self.ratkocmd(message)\\n'\n" +
            "    s = s.replace(marker, insert + marker)\n" +
            "settings.write_text(s)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_developer.py";
    }

    private String hotfixInlineTokenCommand() {
        return "cat > hotfix_inline.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "p = Path('heroku/inline/token_obtainment.py')\n" +
            "s = p.read_text()\n" +
            "hdrs = \"hdrs = inutils.headers.copy()\\n            hdrs.update({'x-aj-referer': 'https://webappinternal.telegram.org/botfather', 'x-requested-with': 'XMLHttpRequest'})\"\n" +
            "s = s.replace('hdrs = self._get_bot_headers()', hdrs)\n" +
            "s = s.replace('if bot_id:\\n            if revoke_token:', 'if bot_id:\\n            ' + hdrs + '\\n            if revoke_token:')\n" +
            "mp = Path('heroku/main.py')\n" +
            "m = mp.read_text()\n" +
            "needle = 'existing = db.get(\"heroku.inline\", \"custom_bot\", False)\\n        except Exception:'\n" +
            "old_insert = 'existing = db.get(\"heroku.inline\", \"custom_bot\", False)\\n            env_bot = os.environ.get(\"HEROKU_CUSTOM_INLINE_BOT\")\\n            if env_bot:\\n                db.set(\"heroku.inline\", \"custom_bot\", env_bot.strip().lstrip(\"@\"))\\n                db.set(\"heroku.inline\", \"bot_token\", None)\\n                existing = env_bot\\n        except Exception:'\n" +
            "insert = 'existing = db.get(\"heroku.inline\", \"custom_bot\", False)\\n            env_bot = os.environ.get(\"HEROKU_CUSTOM_INLINE_BOT\")\\n            if env_bot:\\n                env_bot = env_bot.strip().lstrip(\"@\")\\n                if existing != env_bot:\\n                    db.set(\"heroku.inline\", \"custom_bot\", env_bot)\\n                    db.set(\"heroku.inline\", \"bot_token\", None)\\n                existing = env_bot\\n        except Exception:'\n" +
            "m = m.replace(old_insert, insert)\n" +
            "if needle in m and 'HEROKU_CUSTOM_INLINE_BOT' not in m:\n    m = m.replace(needle, insert)\n" +
            "mp.write_text(m)\n" +
            "p.write_text(s)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_inline.py";
    }

    private String hotfixInfoCommand() {
        return "cat > hotfix_info.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "import re\n" +
            "p = Path('heroku/modules/heroku_info.py')\n" +
            "s = p.read_text()\n" +
            "s = s.replace('platform = utils.get_named_platform()', 'platform = \\\"herokuapk\\\"')\n" +
            "s = s.replace('platform_emoji = utils.get_named_platform_emoji()', 'platform_emoji = \\\"📱\\\"')\n" +
            "marker = '        data = {\\\\n'\n" +
            "helpers = '''        def _herokuapk_host_info():\\n            try:\\n                import json\\n                return json.loads(Path(\"/support/common/host_info.json\").read_text())\\n            except Exception:\\n                return {}\\n\\n        def _herokuapk_host_value(key, default):\\n            return _herokuapk_host_info().get(key, default)\\n\\n        def _herokuapk_safe_cpu_usage():\\n            try:\\n                return utils.get_cpu_usage()\\n            except Exception:\\n                return \"N/A\"\\n\\n        def _herokuapk_safe_ram_usage():\\n            try:\\n                return f\"{utils.get_ram_usage()} MB\"\\n            except Exception:\\n                return \"0 MB\"\\n\\n        def _herokuapk_safe_cpu():\\n            try:\\n                return _herokuapk_host_value(\"cpu\", \"N/A\")\\n            except Exception:\\n                return \"N/A\"\\n\\n'''\n" +
            "if 'def _herokuapk_host_value' not in s and marker in s:\n    s = s.replace(marker, helpers + marker)\n" +
            "s = s.replace('\\\"cpu_usage\\\": utils.get_cpu_usage(),', '\\\"cpu_usage\\\": _herokuapk_host_value(\"cpu_usage\", _herokuapk_safe_cpu_usage()),')\n" +
            "s = s.replace('\\\"cpu_usage\\\": _herokuapk_safe_cpu_usage(),', '\\\"cpu_usage\\\": _herokuapk_host_value(\"cpu_usage\", _herokuapk_safe_cpu_usage()),')\n" +
            "s = s.replace('\\\"ram_usage\\\": f\"{utils.get_ram_usage()} MB\",', '\\\"ram_usage\\\": _herokuapk_host_value(\"ram_usage\", _herokuapk_safe_ram_usage()),')\n" +
            "s = s.replace('\\\"ram_usage\\\": _herokuapk_safe_ram_usage(),', '\\\"ram_usage\\\": _herokuapk_host_value(\"ram_usage\", _herokuapk_safe_ram_usage()),')\n" +
            "s = s.replace('\\\"hostname\\\": lib_platform.node(),', '\\\"hostname\\\": _herokuapk_host_value(\"host\", \"herokuapk\"),')\n" +
            "s = s.replace('\\\"hostname\\\": \"herokuapk\",', '\\\"hostname\\\": _herokuapk_host_value(\"host\", \"herokuapk\"),')\n" +
            "s = s.replace('\\\"cpu\\\": f\"{psutil.cpu_count(logical=False)} ({psutil.cpu_count()}) core(-s); {psutil.cpu_percent()}% total\",', '\\\"cpu\\\": _herokuapk_host_value(\"cpu\", _herokuapk_safe_cpu()),')\n" +
            "s = s.replace('\\\"cpu\\\": _herokuapk_safe_cpu(),', '\\\"cpu\\\": _herokuapk_host_value(\"cpu\", _herokuapk_safe_cpu()),')\n" +
            "p.write_text(s)\n" +
            "up = Path('heroku/utils/platform.py')\n" +
            "u = up.read_text()\n" +
            "helper = '\\n\\ndef _herokuapk_platform_host_info():\\n    try:\\n        import json\\n        return json.loads(open(\\\"/support/common/host_info.json\\\").read())\\n    except Exception:\\n        return {}\\n'\n" +
            "u = u.replace('return json.loads(Path(\\\"/support/common/host_info.json\\\").read_text())', 'return json.loads(open(\\\"/support/common/host_info.json\\\").read())')\n" +
            "if 'def _herokuapk_platform_host_info' not in u:\n    u += helper\n" +
            "cpu_func = 'def get_cpu_usage():\\n    data = _herokuapk_platform_host_info()\\n    value = str(data.get(\\\"cpu_usage\\\", \\\"\\\")).replace(\\\"%\\\", \\\"\\\")\\n    if value and value != \\\"N/A\\\":\\n        return value\\n    return \\\"N/A\\\"\\n'\n" +
            "ram_func = 'def get_ram_usage() -> float:\\n    data = _herokuapk_platform_host_info()\\n    value = str(data.get(\\\"ram_usage\\\", \\\"\\\")).replace(\\\" MB\\\", \\\"\\\")\\n    try:\\n        return round(float(value), 1)\\n    except Exception:\\n        return 0\\n'\n" +
            "u = re.sub(r'def get_ram_usage\\(\\) -> float:.*?(?=\\n\\ndef get_cpu_usage)', ram_func, u, flags=re.S)\n" +
            "u = re.sub(r'def get_cpu_usage\\(\\):.*?(?=\\n\\ninit_ts|\\n\\ndef get_ip_address|\\Z)', cpu_func, u, flags=re.S)\n" +
            "up.write_text(u)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_info.py";
    }

    private String hotfixMetricsCommand() {
        return "cat > hotfix_metrics.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "import re\n" +
            "platform = Path('heroku/utils/platform.py')\n" +
            "s = platform.read_text()\n" +
            "ram_func = '''def get_ram_usage() -> float:\n    \"\"\"Returns current process tree memory usage in MB\"\"\"\n    try:\n        import psutil\n        current_process = psutil.Process(os.getpid())\n        mem = current_process.memory_info()[0] / 2.0**20\n        for child in current_process.children(recursive=True):\n            mem += child.memory_info()[0] / 2.0**20\n        return round(mem, 1)\n    except Exception:\n        return 0\n'''\n" +
            "cpu_func = '''def get_cpu_usage():\n    try:\n        import psutil\n        current_process = psutil.Process(os.getpid())\n        cpu = current_process.cpu_percent(interval=0.1)\n        for child in current_process.children(recursive=True):\n            try:\n                cpu += child.cpu_percent(interval=0)\n            except Exception:\n                pass\n        return f\"{cpu:.2f}\"\n    except Exception:\n        return \"0.00\"\n'''\n" +
            "s = re.sub(r'def get_ram_usage\\(\\) -> float:.*?(?=\\n\\ndef get_cpu_usage)', ram_func, s, flags=re.S)\n" +
            "s = re.sub(r'def get_cpu_usage\\(\\):.*?(?=\\n\\ninit_ts|\\n\\ndef get_ip_address|\\Z)', cpu_func, s, flags=re.S)\n" +
            "platform.write_text(s)\n" +
            "info = Path('heroku/modules/heroku_info.py')\n" +
            "t = info.read_text()\n" +
            "t = t.replace('\"cpu_usage\": _herokuapk_host_value(\"cpu_usage\", _herokuapk_safe_cpu_usage()),', '\"cpu_usage\": _herokuapk_safe_cpu_usage(),')\n" +
            "t = t.replace('\"ram_usage\": _herokuapk_host_value(\"ram_usage\", _herokuapk_safe_ram_usage()),', '\"ram_usage\": _herokuapk_safe_ram_usage(),')\n" +
            "t = t.replace('\"cpu\": _herokuapk_host_value(\"cpu\", _herokuapk_safe_cpu()),', '\"cpu\": _herokuapk_safe_cpu(),')\n" +
            "info.write_text(t)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_metrics.py";
    }

    private String hotfixRestartCommand() {
        return "cat > hotfix_restart.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "p = Path('heroku/_internal.py')\n" +
            "s = p.read_text()\n" +
            "needle = 'def restart():\\n    if \"--sandbox\" in \" \".join(sys.argv):\\n        exit(0)\\n'\n" +
            "insert = 'def restart():\\n    if os.environ.get(\"HEROKUAPK\") == \"1\":\\n        logging.getLogger().setLevel(logging.CRITICAL)\\n        print(\"Restarting...\")\\n        os.execl(sys.executable, sys.executable, \"-m\", \"heroku\", *sys.argv[1:])\\n\\n    if \"--sandbox\" in \" \".join(sys.argv):\\n        exit(0)\\n'\n" +
            "if 'os.environ.get(\"HEROKUAPK\") == \"1\"' not in s and needle in s:\n    s = s.replace(needle, insert)\n" +
            "p.write_text(s)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_restart.py";
    }

    private String hotfixFinalCommand() {
        return "cat > hotfix_final.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "import re\n" +
            "platform = Path('heroku/utils/platform.py')\n" +
            "s = platform.read_text()\n" +
            "ram_func = '''def get_ram_usage() -> float:\n    \"\"\"Returns current process tree memory usage in MB\"\"\"\n    try:\n        import psutil\n        current_process = psutil.Process(os.getpid())\n        mem = current_process.memory_info()[0] / 2.0**20\n        for child in current_process.children(recursive=True):\n            mem += child.memory_info()[0] / 2.0**20\n        return round(mem, 1)\n    except Exception:\n        return 0\n'''\n" +
            "cpu_func = '''def get_cpu_usage():\n    try:\n        import psutil\n        current_process = psutil.Process(os.getpid())\n        cpu = current_process.cpu_percent(interval=0.1)\n        for child in current_process.children(recursive=True):\n            try:\n                cpu += child.cpu_percent(interval=0)\n            except Exception:\n                pass\n        return f\"{cpu:.2f}\"\n    except Exception:\n        return \"0.00\"\n'''\n" +
            "s = re.sub(r'def get_ram_usage\\(\\) -> float:.*?(?=\\n\\ndef get_cpu_usage)', ram_func, s, flags=re.S)\n" +
            "s = re.sub(r'def get_cpu_usage\\(\\):.*?(?=\\n\\ninit_ts|\\n\\ndef get_ip_address|\\Z)', cpu_func, s, flags=re.S)\n" +
            "platform.write_text(s)\n" +
            "info = Path('heroku/modules/heroku_info.py')\n" +
            "t = info.read_text()\n" +
            "t = t.replace('platform = utils.get_named_platform()', 'platform = \"herokuapk\"')\n" +
            "t = t.replace('platform_emoji = utils.get_named_platform_emoji()', 'platform_emoji = \"📱\"')\n" +
            "for old in [\n" +
            "    '\"cpu_usage\": _herokuapk_host_value(\"cpu_usage\", _herokuapk_safe_cpu_usage()),',\n" +
            "    '\"cpu_usage\": _herokuapk_safe_cpu_usage(),',\n" +
            "]:\n    t = t.replace(old, '\"cpu_usage\": utils.get_cpu_usage(),')\n" +
            "for old in [\n" +
            "    '\"ram_usage\": _herokuapk_host_value(\"ram_usage\", _herokuapk_safe_ram_usage()),',\n" +
            "    '\"ram_usage\": _herokuapk_safe_ram_usage(),',\n" +
            "]:\n    t = t.replace(old, '\"ram_usage\": f\"{utils.get_ram_usage()} MB\",')\n" +
            "for old in [\n" +
            "    '\"cpu\": _herokuapk_host_value(\"cpu\", _herokuapk_safe_cpu()),',\n" +
            "    '\"cpu\": _herokuapk_safe_cpu(),',\n" +
            "    '\"cpu\": f\"{psutil.cpu_count(logical=False)} ({psutil.cpu_count()}) core(-s); {psutil.cpu_percent()}% total\",',\n" +
            "]:\n    t = t.replace(old, '\"cpu\": f\"{psutil.cpu_count(logical=False) or psutil.cpu_count()} ({psutil.cpu_count()}) core(-s); {utils.get_cpu_usage()}% total\",')\n" +
            "t = re.sub(r'\\\"ping\\\": .*?,', '\"ping\": getattr(self, \"_herokuapk_last_ping\", round((time.perf_counter_ns() - start) / 10**6, 3)),', t)\n" +
            "needle = '        start = time.perf_counter_ns()\\n        banner_url, force_web_media = self._get_effective_banner()\\n'\n" +
            "insert = '        start = time.perf_counter_ns()\\n        if \"{ping}\" in self._get_effective_info_template():\\n            ping_start = time.perf_counter_ns()\\n            try:\\n                message = await utils.answer(message, self.config[\"ping_emoji\"])\\n                self._herokuapk_last_ping = round((time.perf_counter_ns() - ping_start) / 10**6, 3)\\n            except Exception:\\n                self._herokuapk_last_ping = 0\\n        banner_url, force_web_media = self._get_effective_banner()\\n'\n" +
            "if '_herokuapk_last_ping' not in t and needle in t:\n    t = t.replace(needle, insert)\n" +
            "info.write_text(t)\n" +
            "test = Path('heroku/modules/test.py')\n" +
            "q = test.read_text()\n" +
            "start_idx = q.find('    @loader.command()\\n    async def ping(')\n" +
            "end_idx = q.find('    async def client_ready', start_idx)\n" +
            "if start_idx != -1 and end_idx != -1:\n" +
            "    simple_ping = '''    @loader.command()\n    async def ping(self, message: Message):\n        \"\"\"- Find out your userbot ping\"\"\"\n        start = time.perf_counter_ns()\n        msg = await utils.answer(message, self.config[\"ping_emoji\"])\n        ping = round((time.perf_counter_ns() - start) / 10**6, 3)\n        await utils.answer(msg, f\"<b>Ping:</b> <code>{ping}</code> ms\\n<b>Uptime:</b> <code>{utils.formatted_uptime()}</code>\")\n\n'''\n" +
            "    q = q[:start_idx] + simple_ping + q[end_idx:]\n" +
            "    test.write_text(q)\n" +
            "help_file = Path('heroku/modules/help.py')\n" +
            "h = help_file.read_text()\n" +
            "start_idx = h.find('    @loader.command(\\n        ru_doc=\"[args] | Помощь')\n" +
            "end_idx = h.find('    @loader.command(\\n        ru_doc=\"| Ссылка', start_idx)\n" +
            "if start_idx != -1 and end_idx != -1:\n" +
            "    simple_help = '''    @loader.command(\n        ru_doc=\"[args] | Помощь с вашими модулями!\",\n        ua_doc=\"[args] | допоможіть з вашими модулями!\",\n        de_doc=\"[args] | Hilfe mit deinen Modulen!\",\n    )\n    async def help(self, message: Message):\n        \"\"\"[args] | help with your modules!\"\"\"\n        args = utils.get_args_raw(message)\n        if args:\n            await self.modhelp(message, args)\n            return\n        lines = []\n        for mod in self.allmodules.modules:\n            if not getattr(mod, \"commands\", None):\n                continue\n            try:\n                name = mod.strings[\"name\"]\n            except Exception:\n                name = getattr(mod, \"name\", mod.__class__.__name__)\n            cmds = sorted(mod.commands.keys())\n            if cmds:\n                lines.append(f\"<b>{utils.escape_html(str(name))}</b>: <code>{'</code> <code>'.join(cmds)}</code>\")\n        text = \"<b>Heroku modules:</b>\\n\" + \"\\n\".join(lines)\n        await utils.answer(message, text[:3900])\n\n'''\n" +
            "    h = h[:start_idx] + simple_help + h[end_idx:]\n" +
            "    help_file.write_text(h)\n" +
            "PY\n" +
            ".venv/bin/python hotfix_final.py";
    }

    private void startProcess(String command, boolean interactive) {
        startProcess(command, interactive, false, false, command, false);
    }

    private void startProcess(String command, boolean interactive, boolean openSupportOnSuccess) {
        startProcess(command, interactive, openSupportOnSuccess, false, command, false);
    }

    private void startProcess(String command, boolean interactive, boolean openSupportOnSuccess, boolean autoRestart) {
        startProcess(command, interactive, openSupportOnSuccess, autoRestart, command, false);
    }

    private void startProcess(String command, boolean interactive, boolean openSupportOnSuccess, boolean autoRestart, String label) {
        startProcess(command, interactive, openSupportOnSuccess, autoRestart, label, false);
    }

    private void startProcess(String command, boolean interactive, boolean openSupportOnSuccess, boolean autoRestart, String label, boolean cleanupBeforeFirstRun) {
        runTask(() -> {
            acquireWakeLock();
            startHostMetricsWriter();
            try { writeHostInfo(); } catch (Exception ignored) {}
            if (!new File(supportDir, "execInProot.sh").exists() || !new File(rootfsDir, "bin/sh").exists()) {
                log("[ERROR] Linux is not installed. Press LINUX first.");
                return;
            }
            setupRootfs();
            repairRootfs();
            if (!testProotRuntime()) {
                log("[ERROR] Linux rootfs is broken. Press LINUX again.");
                if (autoRestart) botSupervisorActive = false;
                return;
            }
            if (cleanupBeforeFirstRun) {
                runCleanupBlocking();
            }
            boolean firstRun = true;
            while (true) {
                log((firstRun ? "[CMD] " : "[RESTART] ") + label);
                ProcessBuilder pb = new ProcessBuilder(prootCommand(command));
                pb.directory(baseDir);
                pb.environment().putAll(prootEnv());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                currentProcess = process;
                refreshProcessUiState();
                pumpOutput(process.getInputStream());
                int code = process.waitFor();
                if (currentProcess == process) currentProcess = null;
                refreshProcessUiState();
                log("[EXIT] code " + code);
                if (!interactive) log("[DONE] Command finished");
                if (code == 0 && openSupportOnSuccess) {
                    log("[INFO] Opening support chat @herokuapk");
                    runOnUiThread(this::openSupportChat);
                }
                if (!(interactive && autoRestart && botAutoRestartEnabled && !manualStop)) break;
                if (code == 126 || code == 127) {
                    log("[ERROR] Startup command failed. Auto-restart stopped.");
                    break;
                }
                if (code == 143 && manualStop) break;
                log("[INFO] Userbot exited. Restarting in 3 seconds...");
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                firstRun = false;
            }
            if (autoRestart) botSupervisorActive = false;
            refreshProcessUiState();
            releaseWakeLock();
        });
    }

    private void runCleanupBlocking() {
        try {
            ProcessBuilder pb = new ProcessBuilder(prootCommand(cleanupStaleHerokuCommand()));
            pb.directory(baseDir);
            pb.environment().putAll(prootEnv());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.waitFor();
            log("[INFO] Stale userbot processes cleaned");
        } catch (Exception e) {
            log("[WARN] Cleanup failed: " + e.getMessage());
        }
    }

    private void pumpOutput(InputStream stream) {
        new Thread(() -> {
            try (InputStream in = stream) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    appendOutput(new String(buffer, 0, read));
                }
            } catch (Exception e) {
                log("[OUTPUT ERROR] " + e.getMessage());
            }
        }).start();
    }

    private void sendInput() {
        String text = inputField.getText().toString();
        inputField.setText("");
        if (waitingForInlineBot) {
            saveInlineBotUsername(text);
            return;
        }

        if (waitingForSessionName) {
            saveNewSession(text);
            return;
        }

        if (waitingForTerminalName) {
            saveNewTerminal(text);
            return;
        }

        Process terminal = terminalProcesses.get(selectedTerminalName());
        if (terminal != null && terminal.isAlive()) {
            try {
                OutputStream os = terminal.getOutputStream();
                os.write((text + "\n").getBytes());
                os.flush();
                log("[INPUT -> " + selectedTerminalName() + "] sent");
            } catch (Exception e) {
                log("[INPUT ERROR] " + e.getMessage());
            }
            return;
        }

        if (currentProcess == null) {
            log("[ERROR] No running process. Open a terminal session or start userbot first.");
            return;
        }
        try {
            OutputStream os = currentProcess.getOutputStream();
            os.write((text + "\n").getBytes());
            os.flush();
            log("[INPUT] sent");
        } catch (Exception e) {
            log("[INPUT ERROR] " + e.getMessage());
        }
    }

    private void stopCurrentProcess() {
        closeMenu();
        manualStop = true;
        botAutoRestartEnabled = false;
        botSupervisorActive = false;
        if (currentProcess != null) {
            currentProcess.destroy();
            try { currentProcess.destroyForcibly(); } catch (Exception ignored) {}
            log("[INFO] Process stopped");
            currentProcess = null;
        }
        forceStopHerokuProcesses();
        refreshProcessUiState();
        releaseWakeLock();
    }

    private void forceStopHerokuProcesses() {
        new Thread(() -> {
            try {
                if (!new File(supportDir, "proot").exists() || !new File(rootfsDir, "bin/sh").exists()) return;
                ProcessBuilder pb = new ProcessBuilder(prootCommand(cleanupStaleHerokuCommand()));
                pb.directory(baseDir);
                pb.environment().putAll(prootEnv());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor();
                log("[INFO] Stale userbot processes cleaned");
            } catch (Exception e) {
                log("[WARN] Cleanup failed: " + e.getMessage());
            }
        }).start();
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Heroku Host logs", logConsole.getText().toString()));
        log("[INFO] Logs copied to clipboard");
    }

    private void clearLogs() {
        runOnUiThread(() -> logConsole.setText("Logs cleared.\n"));
        if (followOutput) scrollToBottomSoon();
        refreshProcessUiState();
    }

    private void openSupportChat() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL));
            startActivity(intent);
        } catch (Exception e) {
            log("[WARN] Could not open support chat: " + e.getMessage());
            log("[INFO] Support: " + SUPPORT_URL);
        }
    }

    private void openGithubRepo() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL));
            startActivity(intent);
        } catch (Exception e) {
            log("[WARN] Could not open GitHub: " + e.getMessage());
        }
    }

    private void openLatestRelease() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL));
            startActivity(intent);
        } catch (Exception e) {
            log("[WARN] Could not open releases: " + e.getMessage());
            openGithubRepo();
        }
    }

    private File inlineBotFile() {
        String profile = selectedSessionName();
        if (profile.equals("main")) return new File(baseDir, "inline_bot_username.txt");
        return new File(baseDir, "inline_bot_username-" + profile + ".txt");
    }

    private String getInlineBotUsername() {
        try {
            File file = inlineBotFile();
            if (!file.exists()) return "";
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = in.read(data);
                if (read <= 0) return "";
            }
            return new String(data).trim().replace("@", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isInlineBotUsernameValid(String username) {
        if (username == null) return false;
        username = username.trim().replace("@", "");
        if (username.length() <= 4 || !username.toLowerCase().endsWith("bot")) return false;
        for (int i = 0; i < username.length(); i++) {
            char ch = username.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) return false;
        }
        return true;
    }

    private void askInlineBotUsername() {
        waitingForInlineBot = true;
        runOnUiThread(() -> {
            inputField.setText("");
            inputField.setHint("inline bot username, e.g. my_cool_bot");
        });
        refreshProcessUiState();
        log("[SETUP] Enter inline bot username first. It must end with 'bot'.");
        log("[SETUP] Create it in @BotFather if you don't have one, then type username below and press SEND.");
    }

    private void saveInlineBotUsername(String username) {
        username = username == null ? "" : username.trim().replace("@", "");
        if (!isInlineBotUsernameValid(username)) {
            log("[ERROR] Invalid inline bot username. Use only a-z, 0-9, _, and it must end with bot.");
            askInlineBotUsername();
            return;
        }
        try {
            writeFile(inlineBotFile(), username + "\n");
            waitingForInlineBot = false;
            log("[OK] Inline bot username saved: @" + username);
            startInteractiveBot();
        } catch (Exception e) {
            log("[ERROR] Failed to save inline bot username: " + e.getMessage());
        }
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1024 * 64];
        int len;
        while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
