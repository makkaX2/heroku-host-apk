package org.zet.zov;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.system.Os;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
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
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MainActivity extends AppCompatActivity {
    private TextView logConsole;
    private TextView statusText;
    private TextView forceUpdateBodyText;
    private ScrollView logScroll;
    private View buttonsPanel;
    private View startGroup;
    private View runningGroup;
    private Button setupBtn;
    private Button cancelInstallBtn;
    private View forceUpdateOverlay;
    private volatile boolean installCancelled = false;
    private Thread installThread;
    private Process currentProcess;
    private File baseDir;
    private File rootfsDir;
    private File supportDir;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean manualStop = false;
    private boolean botAutoRestartEnabled = false;
    private boolean botSupervisorActive = false;
    private boolean scrollScheduled = false;
    private Thread keepAliveWatchdogThread;
    private volatile boolean keepAliveWatchdogRunning = false;
    private long lastCpuTotal = 0;
    private long lastCpuIdle = 0;
    private double lastCpuPercent = -1;
    private String lastKeepAliveSignature = "";
    private boolean updateRequired = false;

    private static final String GITHUB_RELEASES_URL = "https://github.com/ziwupa/heroku-host-apk/releases/latest";
    private static final String GITHUB_REPO_URL = "https://github.com/ziwupa/heroku-host-apk";
    private static final String REMOTE_BUILD_GRADLE_URL = "https://raw.githubusercontent.com/ziwupa/heroku-host-apk/main/app/build.gradle";
    private static final int MAX_LOG_CHARS = 90000;
    private static final String PATCH_MARKER = ".herokuapk_patch_v33";
    private static final String UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View rootView = findViewById(R.id.rootView);
        logConsole = findViewById(R.id.logConsole);
        logScroll = findViewById(R.id.logScroll);
        statusText = findViewById(R.id.statusText);
        forceUpdateBodyText = findViewById(R.id.forceUpdateBodyText);
        forceUpdateOverlay = findViewById(R.id.forceUpdateOverlay);
        buttonsPanel = findViewById(R.id.buttonsPanel);
        startGroup = findViewById(R.id.startGroup);
        runningGroup = findViewById(R.id.runningGroup);
        setupBtn = findViewById(R.id.setupBtn);
        cancelInstallBtn = findViewById(R.id.cancelInstallBtn);
        Button startBotBtn = findViewById(R.id.startBotBtn);
        Button stopBtn = findViewById(R.id.stopBtn);
        Button restartBtn = findViewById(R.id.restartBtn);
        Button updateNowBtn = findViewById(R.id.updateNowBtn);
        Button checkAgainBtn = findViewById(R.id.checkAgainBtn);

        baseDir = new File(getFilesDir(), "userland");
        rootfsDir = new File(baseDir, "rootfs");
        supportDir = new File(baseDir, "support");
        baseDir.mkdirs();
        supportDir.mkdirs();

        requestBackgroundWorkPermission();
        startKeepAliveWatchdog();

        startBotBtn.setOnClickListener(v -> startInteractiveBot());
        stopBtn.setOnClickListener(v -> stopCurrentProcess());
        restartBtn.setOnClickListener(v -> restartBot());
        cancelInstallBtn.setOnClickListener(v -> cancelInstall());
        updateNowBtn.setOnClickListener(v -> openLatestRelease());
        checkAgainBtn.setOnClickListener(v -> {
            log("[UPDATE] Manual check requested");
            checkForUpdatesAsync();
        });

        startAnimatedBackground(rootView);

        log("[INFO] Heroku Host ready");
        checkForUpdatesAsync();

        if (!isRootfsValid() || !isHerokuInstalledForSelectedAccount()) {
            setupBtn.setVisibility(View.VISIBLE);
            log("[INFO] Press INSTALL to set up the environment.");
            setupBtn.setOnClickListener(v -> startInstall());
        } else {
            log("[INFO] Already installed. Ready.");
            showButtonsPanel();
        }

        refreshProcessUiState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scrollToBottomSoon();
        refreshProcessUiState();
    }

    @Override
    protected void onDestroy() {
        keepAliveWatchdogRunning = false;
        super.onDestroy();
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

    private void startAnimatedBackground(View root) {
        final int bottom = 0xFF050B14;   // fixed — matches navigation bar
        final int mid    = 0xFF0A1A30;   // fixed
        final int topA   = 0xFF112844;
        final int topB   = 0xFF1B3A63;
        final GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ topA, mid, bottom });
        root.setBackground(gd);
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), topA, topB);
        anim.setDuration(16000);                       // slow: ~32s full breath
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            int top = (int) a.getAnimatedValue();
            gd.setColors(new int[]{ top, mid, bottom });
            getWindow().setStatusBarColor(top);        // status bar follows the top shimmer
        });
        anim.start();
    }

    private void startInstall() {
        installCancelled = false;
        runOnUiThread(() -> {
            setupBtn.setVisibility(View.GONE);
            cancelInstallBtn.setVisibility(View.VISIBLE);
        });
        installThread = new Thread(() -> {
            acquireWakeLock();
            try {
                if (!isRootfsValid()) installLinux();
                if (!isHerokuInstalledForSelectedAccount()) installHeroku();
                showButtonsPanel();
            } catch (Exception e) {
                if (installCancelled) log("[INFO] Installation cancelled.");
                else log("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                runOnUiThread(() -> setupBtn.setVisibility(View.VISIBLE));
            } finally {
                runOnUiThread(() -> cancelInstallBtn.setVisibility(View.GONE));
                releaseWakeLock();
            }
        });
        installThread.start();
    }

    private void cancelInstall() {
        installCancelled = true;
        if (currentProcess != null) {
            currentProcess.destroy();
            try { currentProcess.destroyForcibly(); } catch (Exception ignored) {}
            currentProcess = null;
        }
        if (installThread != null) installThread.interrupt();
        log("[INFO] Cancelling installation...");
    }

    private void showButtonsPanel() {
        runOnUiThread(() -> {
            logScroll.setVisibility(View.GONE);
            setupBtn.setVisibility(View.GONE);
            cancelInstallBtn.setVisibility(View.GONE);
            buttonsPanel.setVisibility(View.VISIBLE);
        });
    }

    private void restartBot() {
        new Thread(() -> {
            stopCurrentProcess();
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            startInteractiveBot();
        }).start();
    }

    private void updateStatusText() {
        if (statusText == null) return;
        runOnUiThread(() -> {
            boolean running = currentProcess != null && currentProcess.isAlive();
            statusText.setText(running ? "● running" : "○ stopped");
        });
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

    private void refreshProcessUiState() {
        syncForcedUpdateUi();
        syncKeepAliveState();
        updateStatusText();
        updateContextButtons();
    }

    private boolean isBotActive() {
        return botSupervisorActive || (currentProcess != null && currentProcess.isAlive());
    }

    private void updateContextButtons() {
        if (startGroup == null || runningGroup == null) return;
        runOnUiThread(() -> {
            boolean active = isBotActive();
            startGroup.setVisibility(active ? View.GONE : View.VISIBLE);
            runningGroup.setVisibility(active ? View.VISIBLE : View.GONE);
        });
    }

    private void syncForcedUpdateUi() {
        if (forceUpdateOverlay == null) return;
        runOnUiThread(() -> forceUpdateOverlay.setVisibility(updateRequired ? View.VISIBLE : View.GONE));
    }

    private void checkForUpdatesAsync() {
        new Thread(() -> {
            try {
                PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                int localVersionCode = packageInfo.versionCode;
                String localVersionName = packageInfo.versionName == null ? "?" : packageInfo.versionName;
                String remoteBuildGradle = fetchText(REMOTE_BUILD_GRADLE_URL);
                int remoteVersionCode = parseVersionCode(remoteBuildGradle);
                String remoteVersionName = parseVersionName(remoteBuildGradle);
                if (remoteVersionCode > localVersionCode) {
                    updateRequired = true;
                    String text = "Update available: local v" + localVersionName + " (" + localVersionCode
                        + ") -> remote v" + remoteVersionName + " (" + remoteVersionCode
                        + "). Tap to open latest release.";
                    showForcedUpdate(text, true);
                    log("[UPDATE] " + text);
                } else {
                    updateRequired = false;
                    showForcedUpdate("", false);
                    log("[UPDATE] App is up to date: v" + localVersionName + " (" + localVersionCode + ")");
                }
            } catch (Exception e) {
                updateRequired = false;
                showForcedUpdate("", false);
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
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
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

    private void showForcedUpdate(String text, boolean visible) {
        runOnUiThread(() -> {
            if (forceUpdateOverlay != null)
                forceUpdateOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (forceUpdateBodyText != null)
                forceUpdateBodyText.setText(text);
        });
    }

    private boolean hasActiveRuntime() {
        return currentProcess != null && currentProcess.isAlive();
    }

    private String activeRuntimeSummary() {
        if (currentProcess != null && currentProcess.isAlive())
            return botSupervisorActive ? "Userbot active with watchdog" : "Userbot active";
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
            if (hasActiveRuntime() || botSupervisorActive || wifiLock == null || !wifiLock.isHeld()) return;
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
            scrollToBottomSoon();
        });
    }

    private void scrollToBottomSoon() {
        if (scrollScheduled || logScroll == null || logScroll.getVisibility() != View.VISIBLE) return;
        scrollScheduled = true;
        logScroll.postDelayed(() -> {
            scrollScheduled = false;
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }, 80);
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

    private String selectedSessionName() {
        return "main";
    }

    private String herokuDirName() {
        return "Heroku";
    }

    private String herokuPath() {
        return "/root/Heroku";
    }

    private File herokuRootfsDir() {
        return new File(rootfsDir, "root/Heroku");
    }

    private boolean isHerokuInstalledForSelectedAccount() {
        File dir = herokuRootfsDir();
        return (new File(dir, "heroku").exists() && fileExistsOrSymlink(new File(dir, ".venv/bin/python")))
            || (new File(dir, "heroku").exists() && fileExistsOrSymlink(new File(dir, ".venv/bin/python3")));
    }

    private boolean fileExistsOrSymlink(File file) {
        try {
            return file.exists() || Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            return file.exists();
        }
    }

    private File inlineBotFile() {
        return new File(baseDir, "inline_bot_username.txt");
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
        runOnUiThread(() -> {
            EditText input = new EditText(this);
            input.setHint("my_cool_bot");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            new AlertDialog.Builder(this)
                .setTitle("Inline Bot Username")
                .setMessage("Enter your @BotFather bot username (must end with 'bot'):")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> saveInlineBotUsername(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
        });
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
            log("[OK] Inline bot username saved: @" + username);
            startInteractiveBot();
        } catch (Exception e) {
            log("[ERROR] Failed to save inline bot username: " + e.getMessage());
        }
    }

    private void startInteractiveBot() {
        String inlineBot = getInlineBotUsername();
        if (!isInlineBotUsernameValid(inlineBot)) {
            askInlineBotUsername();
            return;
        }
        if (!isHerokuInstalledForSelectedAccount()) {
            log("[ERROR] Heroku is not installed. Press INSTALL first.");
            return;
        }
        if (botSupervisorActive || (currentProcess != null && currentProcess.isAlive())) {
            log("[INFO] Userbot is already running. Press STOP first if you want to restart it.");
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

    private void stopCurrentProcess() {
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

    private void startProcess(String command, boolean interactive, boolean openSupportOnSuccess, boolean autoRestart, String label, boolean cleanupBeforeFirstRun) {
        runTask(() -> {
            try { writeHostInfo(); } catch (Exception ignored) {}
            if (!new File(supportDir, "execInProot.sh").exists() || !new File(rootfsDir, "bin/sh").exists()) {
                log("[ERROR] Linux is not installed. Press INSTALL first.");
                if (autoRestart) botSupervisorActive = false;
                return;
            }
            setupRootfs();
            repairRootfs();
            if (!testProotRuntime()) {
                log("[ERROR] Linux rootfs is broken. Try re-installing.");
                if (autoRestart) botSupervisorActive = false;
                return;
            }
            if (cleanupBeforeFirstRun) runCleanupBlocking();
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

    private void openLatestRelease() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)));
            } catch (Exception ignored) {}
        }
    }

    private boolean isRootfsValid() {
        return new File(rootfsDir, "bin/sh").exists() && new File(rootfsDir, "usr/bin/env").exists();
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
        log("[DONE] Linux installed.");
    }

    private void installHeroku() throws Exception {
        String dirName = herokuDirName();
        String path = herokuPath();
        log("[INFO] Installing Heroku...");
        setupRootfs();
        repairRootfs();
        if (!testProotRuntime()) throw new IllegalStateException("Linux runtime is broken.");
        String command = "export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color DEBIAN_FRONTEND=noninteractive && " +
            "dpkg --remove --force-remove-reinstreq --force-depends dbus libpam-systemd systemd-resolved networkd-dispatcher dbus-user-session dconf-service dconf-gsettings-backend libgtk-3-common gsettings-desktop-schemas libgtk-3-bin libgtk-3-0t64 at-spi2-core libdecor-0-plugin-1-gtk 2>/dev/null || true && " +
            "apt update && apt install -y --no-install-recommends ca-certificates coreutils git python3 python3-pip python3-venv build-essential libcairo2 libmagic1 openssl && " +
            "cd /root && if [ ! -d " + dirName + " ]; then git clone https://github.com/coddrago/Heroku " + dirName + "; fi && " +
            "cd " + path + " && python3 -m venv .venv && " +
            ".venv/bin/python -m pip install --upgrade pip wheel setuptools && " +
            ".venv/bin/python -m pip install -r requirements.txt && " +
            ".venv/bin/python -c \"import hashlib; open('.requirements_hash','w').write(hashlib.sha256(open('requirements.txt','rb').read()).hexdigest())\"";
        log("[CMD] INSTALL HEROKU");
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
        if (installCancelled) return;
        log("[EXIT] code " + code);
        if (code != 0) throw new RuntimeException("Heroku installation failed with exit code " + code);
        log("[DONE] Heroku installed.");
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
                if (installCancelled) throw new java.io.InterruptedIOException("cancelled");
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
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new java.io.BufferedInputStream(new GZIPInputStream(new FileInputStream(tarGz))))) {
            TarArchiveEntry entry;
            byte[] buf = new byte[1024 * 64];
            int count = 0;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (installCancelled) throw new InterruptedException("cancelled");
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
        java.io.File extDir = getExternalFilesDir(null);
        String extPath = extDir != null ? extDir.getAbsolutePath() : "/sdcard";
        env.put("EXTRA_BINDINGS", "-b " + extPath + ":/storage/internal -b " + supportDir.getAbsolutePath() + "/host_info.json:/support/common/host_info.json" + procBindings);
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
            if (code != 0) { log("[RUNTIME TEST FAILED] " + out.toString().trim()); return false; }
            return out.toString().contains("PROOT_OK");
        } catch (Exception e) {
            log("[RUNTIME TEST ERROR] " + e.getMessage());
            return false;
        }
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
            long totalAll = user + nice + system + idle + iowait + irq + softirq + steal;
            if (lastCpuTotal == 0) { lastCpuTotal = totalAll; lastCpuIdle = idleAll; return lastCpuPercent; }
            long totalDelta = totalAll - lastCpuTotal;
            long idleDelta = idleAll - lastCpuIdle;
            lastCpuTotal = totalAll;
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
                        if (parsed >= 0) { lastCpuPercent = Math.max(0, Math.min(100, parsed)); return lastCpuPercent; }
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
                if ((ch >= '0' && ch <= '9') || ch == '.') start--;
                else break;
            }
            if (start + 1 < i) {
                try { values.add(Double.parseDouble(line.substring(start + 1, i))); } catch (Exception ignored) {}
            }
        }
        if (values.isEmpty()) return -1;
        if (values.get(0) > 100 && values.size() > 2) return values.get(1) + values.get(2);
        return values.get(0);
    }

    private void copy(InputStream in, java.io.OutputStream out) throws Exception {
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
