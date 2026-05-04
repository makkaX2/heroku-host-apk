package org.zet.zov;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.system.Os;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

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
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class MainActivity extends AppCompatActivity {
    private TextView logConsole;
    private ScrollView logScroll;
    private EditText inputField;
    private Process currentProcess;
    private File baseDir;
    private File rootfsDir;
    private File supportDir;
    private PowerManager.WakeLock wakeLock;
    private boolean waitingForInlineBot = false;

    private static final String UBUNTU_BASE = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logConsole = findViewById(R.id.logConsole);
        logScroll = findViewById(R.id.logScroll);
        inputField = findViewById(R.id.inputField);
        Button installLinuxBtn = findViewById(R.id.installLinuxBtn);
        Button installHerokuBtn = findViewById(R.id.installHerokuBtn);
        Button startBotBtn = findViewById(R.id.startBotBtn);
        Button stopBtn = findViewById(R.id.stopBtn);
        Button copyLogsBtn = findViewById(R.id.copyLogsBtn);
        Button sendInputBtn = findViewById(R.id.sendInputBtn);

        baseDir = new File(getFilesDir(), "userland");
        rootfsDir = new File(baseDir, "rootfs");
        supportDir = new File(baseDir, "support");
        baseDir.mkdirs();
        supportDir.mkdirs();
        requestBackgroundWorkPermission();

        installLinuxBtn.setOnClickListener(v -> runTask(this::installLinux));
        installHerokuBtn.setOnClickListener(v -> runTask(this::installHeroku));
        startBotBtn.setOnClickListener(v -> startInteractiveBot());
        stopBtn.setOnClickListener(v -> stopCurrentProcess());
        copyLogsBtn.setOnClickListener(v -> copyLogs());
        sendInputBtn.setOnClickListener(v -> sendInput());

        log("[INFO] Heroku Host ready");
        log("[INFO] Step 1: INSTALL LINUX");
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
            if (currentProcess != null && currentProcess.isAlive()) return;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
    }

    private void log(String msg) {
        appendOutput(msg + "\n");
    }

    private void appendOutput(String msg) {
        runOnUiThread(() -> {
            logConsole.append(msg);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
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

        writeFile(new File(rootfsDir, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        writeFile(new File(rootfsDir, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        writeFile(new File(rootfsDir, "etc/hostname"), "localhost\n");
        writeFile(new File(support, ".proot_version"), ".a10\n");
        writeFile(new File(support, "ld.so.preload"), "");
        writeFile(new File(support, "nosudo"), "#!/bin/sh\nexec \"$@\"\n");
        writeFile(new File(support, "userland_profile.sh"), "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
        writeFile(new File(support, "version"), "Linux version 6.1.0 (userland@android) #1 SMP\n");
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
        return new String[] {
            supportDir.getAbsolutePath() + "/proot",
            "-r", rootfsDir.getAbsolutePath(),
            "-v", "-1",
            "-p",
            "--sysvipc",
            "-H",
            "-0",
            "-l",
            "-L",
            "-b", "/sys",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/data",
            "-b", "/mnt",
            "-b", "/proc/mounts:/etc/mtab",
            "-b", "/:/host-rootfs",
            "-b", new File(rootfsDir, "support").getAbsolutePath() + ":/support",
            "-b", new File(rootfsDir, "support/nosudo").getAbsolutePath() + ":/usr/local/bin/sudo",
            "-b", new File(rootfsDir, "support/userland_profile.sh").getAbsolutePath() + ":/etc/profile.d/userland_profile.sh",
            "-b", new File(rootfsDir, "support/ld.so.preload").getAbsolutePath() + ":/etc/ld.so.preload",
            "-b", supportDir.getAbsolutePath() + ":/support/common",
            "-w", "/root",
            shellPath(),
            "-c",
            command
        };
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
        env.put("EXTRA_BINDINGS", "-b " + getExternalFilesDir(null).getAbsolutePath() + ":/storage/internal" + procBindings);
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
        startProcess("export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color DEBIAN_FRONTEND=noninteractive && " +
            "dpkg --remove --force-remove-reinstreq --force-depends dbus libpam-systemd systemd-resolved networkd-dispatcher dbus-user-session dconf-service dconf-gsettings-backend libgtk-3-common gsettings-desktop-schemas libgtk-3-bin libgtk-3-0t64 at-spi2-core libdecor-0-plugin-1-gtk 2>/dev/null || true && " +
            "apt update && apt install -y --no-install-recommends ca-certificates coreutils git python3 python3-pip python3-venv build-essential libcairo2 libmagic1 openssl && " +
            "cd /root && if [ ! -d Heroku ]; then git clone https://github.com/coddrago/Heroku; fi && " +
            "cd /root/Heroku && python3 -m venv .venv && " +
            ".venv/bin/python -m pip install --upgrade pip wheel setuptools && " +
            ".venv/bin/python -m pip install -r requirements.txt && " +
            hotfixInlineTokenCommand() + " && " +
            ".venv/bin/python -c \"import hashlib; open('.requirements_hash','w').write(hashlib.sha256(open('requirements.txt','rb').read()).hexdigest())\"", false);
    }

    private void startInteractiveBot() {
        String inlineBot = getInlineBotUsername();
        if (!isInlineBotUsernameValid(inlineBot)) {
            askInlineBotUsername();
            return;
        }

        startProcess("export HOME=/root PATH=/root/Heroku/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color PYTHONUNBUFFERED=1 HEROKU_CUSTOM_INLINE_BOT='" + inlineBot + "' && cd /root/Heroku && " +
            hotfixInlineTokenCommand() + " && " +
            ".venv/bin/python -u -m heroku --no-web --root", true);
    }

    private String hotfixInlineTokenCommand() {
        return "cat > /root/Heroku/hotfix_inline.py <<'PY'\n" +
            "from pathlib import Path\n" +
            "p = Path('heroku/inline/token_obtainment.py')\n" +
            "s = p.read_text()\n" +
            "hdrs = \"hdrs = inutils.headers.copy()\\n            hdrs.update({'x-aj-referer': 'https://webappinternal.telegram.org/botfather', 'x-requested-with': 'XMLHttpRequest'})\"\n" +
            "s = s.replace('hdrs = self._get_bot_headers()', hdrs)\n" +
            "s = s.replace('if bot_id:\\n            if revoke_token:', 'if bot_id:\\n            ' + hdrs + '\\n            if revoke_token:')\n" +
            "mp = Path('heroku/main.py')\n" +
            "m = mp.read_text()\n" +
            "needle = 'existing = db.get(\"heroku.inline\", \"custom_bot\", False)\\n        except Exception:'\n" +
            "insert = 'existing = db.get(\"heroku.inline\", \"custom_bot\", False)\\n            env_bot = os.environ.get(\"HEROKU_CUSTOM_INLINE_BOT\")\\n            if env_bot:\\n                db.set(\"heroku.inline\", \"custom_bot\", env_bot.strip().lstrip(\"@\"))\\n                db.set(\"heroku.inline\", \"bot_token\", None)\\n                existing = env_bot\\n        except Exception:'\n" +
            "if needle in m and 'HEROKU_CUSTOM_INLINE_BOT' not in m:\n    mp.write_text(m.replace(needle, insert))\n" +
            "p.write_text(s)\n" +
            "PY\n" +
            ".venv/bin/python /root/Heroku/hotfix_inline.py";
    }

    private void startProcess(String command, boolean interactive) {
        runTask(() -> {
            acquireWakeLock();
            if (!new File(supportDir, "execInProot.sh").exists() || !new File(rootfsDir, "bin/sh").exists()) {
                log("[ERROR] Linux is not installed. Press INSTALL LINUX first.");
                return;
            }
            setupRootfs();
            repairRootfs();
            if (!testProotRuntime()) {
                log("[ERROR] Linux rootfs is broken. Press INSTALL LINUX again.");
                return;
            }
            log("[CMD] " + command);
            ProcessBuilder pb = new ProcessBuilder(prootCommand(command));
            pb.directory(baseDir);
            pb.environment().putAll(prootEnv());
            pb.redirectErrorStream(true);
            currentProcess = pb.start();
            pumpOutput(currentProcess.getInputStream());
            int code = currentProcess.waitFor();
            log("[EXIT] code " + code);
            if (!interactive) log("[DONE] Command finished");
            releaseWakeLock();
        });
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

        if (currentProcess == null) {
            log("[ERROR] No running process");
            return;
        }
        try {
            OutputStream os = currentProcess.getOutputStream();
            os.write((text + "\n").getBytes());
            os.flush();
            log("[INPUT] " + text);
        } catch (Exception e) {
            log("[INPUT ERROR] " + e.getMessage());
        }
    }

    private void stopCurrentProcess() {
        if (currentProcess != null) {
            currentProcess.destroy();
            log("[INFO] Process stopped");
        }
        releaseWakeLock();
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Heroku Host logs", logConsole.getText().toString()));
        log("[INFO] Logs copied to clipboard");
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
        waitingForInlineBot = true;
        runOnUiThread(() -> {
            inputField.setText("");
            inputField.setHint("inline bot username, e.g. my_cool_bot");
        });
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
