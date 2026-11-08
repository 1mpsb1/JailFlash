package ru.cuxtoms.flashx;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LedController {

    public static final String METHOD_AUTO = "auto";
    private static final String PREFS = "flashx_state";
    private static final LedController INSTANCE = new LedController();

    public static final class Led {
        public String name = "";
        public String path = "";
        public String switchPath = "";
        public String switchName = "";
        public int channel;
        public int max = 255;
        public int maxSwitchCurrent;
        public boolean enabled = true;
        public int percent = 100;
        public final Set<String> attrs = new HashSet<>();

        public int rawValue() {
            if (percent <= 0) return 0;
            if (percent >= 100) return max;
            int v = (int) Math.round(max * (percent / 100.0));
            return Math.max(1, Math.min(max, v));
        }
    }

    public interface Listener {
        void onBusy(boolean busy, String status);
        void onLedsChanged();
        void onMessage(String message);
    }

    public interface RawCallback {
        void onResult(String result);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Led> leds = new ArrayList<>();
    private final Map<String, int[]> savedLedState = new HashMap<>();

    private Context appContext;
    private SharedPreferences prefs;
    private Listener listener;

    private boolean rootOk;
    private boolean discovering;
    private boolean pendingCleanup;
    private boolean on;
    private boolean boost;
    private boolean keepScreenOn;
    private boolean smoothUp;
    private boolean smoothDown;
    private boolean autoHook = true;
    private boolean unlockedMax;
    private boolean tempMode = false;
    private int smoothMs = 700;
    private boolean capture = true;
    private boolean warnDismissed;
    private boolean snapToEdges = true;
    private boolean stockWarnAck = false;
    private boolean appliedOn;
    private int masterPercent = 30;
    private int tempPercent = 50;
    private int tilePercent = 30;
    private int tileTemp = 50;
    private boolean tileBoost;
    private boolean tileTempMode = false;
    private int autoOffMin;
    private String rootError = "";
    private String allLedNames = "";
    private String lastCommand = "";
    private String lastCommandOutput = "";
    private long lastErrorAt;

    private boolean applying = false;
    private boolean pendingApply = false;
    private long lastApplyTime = 0;

    private void scheduleApply(long extraDelay) {
        long now = System.currentTimeMillis();
        long delay = 40 - (now - lastApplyTime);
        if (delay < 0) delay = 0;
        delay += extraDelay;

        main.removeCallbacks(applyRunnable);
        main.postDelayed(applyRunnable, delay);
    }

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (LedController.this) {
                if (applying) return;
                applying = true;
                lastApplyTime = System.currentTimeMillis();
            }
            io.execute(new Runnable() {
                @Override
                public void run() {
                    doApply();
                    boolean loop;
                    synchronized (LedController.this) {
                        applying = false;
                        loop = pendingApply;
                        pendingApply = false;
                    }
                    if (loop) {
                        main.post(new Runnable() {
                            @Override
                            public void run() { scheduleApply(0); }
                        });
                    }
                }
            });
        }
    };

    private LedController() {}

    public static LedController get(Context context) {
        LedController c = INSTANCE;
        if (context != null) c.init(context.getApplicationContext());
        return c;
    }

    private synchronized void init(Context context) {
        if (appContext != null) return;
        appContext = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadPrefs();
    }

    public void setListener(Listener l) { listener = l; }

    public synchronized boolean isRootOk() { return rootOk; }
    public synchronized String getRootError() { return rootError; }
    public synchronized boolean isOn() { return on; }
    public synchronized boolean isBoost() { return boost; }
    public synchronized boolean isKeepScreenOn() { return keepScreenOn; }
    public synchronized int getMasterPercent() { return masterPercent; }
    public synchronized int getTempPercent() { return tempPercent; }
    public synchronized int getAutoOffMin() { return autoOffMin; }
    public synchronized String getAllLedNames() { return allLedNames; }
    public synchronized boolean isAutoHook() { return autoHook; }
    public synchronized boolean isUnlockedMax() { return unlockedMax; }
    public synchronized boolean isTempMode() { return tempMode; }
    public synchronized boolean isSnapToEdges() { return snapToEdges; }
    public synchronized boolean isStockWarnAck() { return stockWarnAck; }

    public synchronized List<Led> getLeds() {
        List<Led> copy = new ArrayList<>(leds.size());
        for (Led l : leds) {
            Led c = new Led();
            c.name = l.name; c.path = l.path; c.switchPath = l.switchPath; c.switchName = l.switchName;
            c.channel = l.channel; c.max = l.max; c.maxSwitchCurrent = l.maxSwitchCurrent;
            c.enabled = l.enabled; c.percent = l.percent; c.attrs.addAll(l.attrs);
            copy.add(c);
        }
        return copy;
    }

    public synchronized String getDiagnostics() {
        StringBuilder b = new StringBuilder();
        b.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        b.append("Root: ").append(rootOk ? "да" : "нет").append('\n');
        b.append("Захват: ").append(capture ? "вкл" : "выкл").append('\n');
        b.append("Авто-хук: ").append(autoHook ? "вкл" : "выкл").append('\n');
        b.append("Режим: ").append(tempMode ? "Температура" : "Обычный").append('\n');
        b.append("Разблокирован максимум: ").append(unlockedMax ? "да" : "нет").append('\n');
        for (Led l : leds) {
            b.append(l.path).append(" | channel=").append(l.channel).append(" max=").append(l.max).append(" мА\n");
        }
        return b.toString();
    }

    public void dumpNow() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                RootShell sh = RootShell.get();
                if (sh.ensureStarted()) dumpDiagnostics(sh);
            }
        });
    }

    private void dumpDiagnostics(RootShell sh) {
        String dump =
                "{\n" +
                "echo '== build =='\n" +
                "getprop ro.product.device; getprop ro.product.model; getprop ro.build.display.id\n" +
                "echo '== leds =='\n" +
                "ls -la /sys/class/leds\n" +
                "echo '== nodes =='\n" +
                "for d in /sys/class/leds/*; do\n" +
                "  [ -e \"$d\" ] || continue\n" +
                "  echo \"### $d\"\n" +
                "  ls -la \"$d\"\n" +
                "  for f in \"$d\"/*; do\n" +
                "    [ -f \"$f\" ] || continue\n" +
                "    echo \"-- ${f##*/}\"\n" +
                "    cat \"$f\" 2>&1 | head -c 160\n" +
                "    echo\n" +
                "  done\n" +
                "done\n" +
                "} > /data/local/tmp/flashx-diag.txt 2>&1\n" +
                "chmod 644 /data/local/tmp/flashx-diag.txt\n";
        sh.exec(dump, 25000);
    }

    public void discover() {
        synchronized (this) {
            if (discovering) return;
            discovering = true;
        }
        postBusy(true, "Проверка root…");
        io.execute(new Runnable() {
            @Override
            public void run() { doDiscover(); }
        });
    }

    private void doDiscover() {
        RootShell sh = RootShell.get();
        boolean ok = sh.ensureStarted();
        if (!ok) {
            synchronized (LedController.this) {
                rootOk = false;
                rootError = sh.getLastError();
                discovering = false;
            }
            postBusy(false, "Нет root");
            postLedsChanged();
            return;
        }

        String script =
                "echo '@ALL|'$(ls /sys/class/leds 2>/dev/null | tr '\\n' ',')\n" +
                "base=$(find /sys/firmware/devicetree/base -maxdepth 8 -type d -name 'qcom,flash_led@*' 2>/dev/null | head -n 1)\n" +
                "for s in /sys/class/leds/led:switch_*; do\n" +
                "  [ -e \"$s\" ] || continue\n" +
                "  sn=\"${s##*/}\"\n" +
                "  si=\"${sn##*_}\"\n" +
                "  m=$(xxd -p -l 4 \"$base/qcom,led_switch_$si/qcom,led-mask\" 2>/dev/null | tr -d '\\n')\n" +
                "  echo \"@SWITCH|$si|$m|$s|$sn|$(cat \"$s/max_current\" 2>/dev/null)\"\n" +
                "done\n" +
                "for d in /sys/class/leds/led:torch_*; do\n" +
                "  [ -e \"$d\" ] || continue\n" +
                "  n=\"${d##*/}\"\n" +
                "  idx=\"${n##*_}\"\n" +
                "  echo \"@TORCH|$d|$n|$idx|$(cat \"$d/max_brightness\" 2>/dev/null)\"\n" +
                "done\n";

        String out = sh.exec(script, 15000);
        if (out == null) {
            synchronized (LedController.this) {
                rootOk = sh.isRootOk();
                rootError = sh.getLastError();
                discovering = false;
            }
            postBusy(false, "Ошибка root");
            postLedsChanged();
            return;
        }

        List<Led> parsed = parseDiscovery(out);
        Collections.sort(parsed, new Comparator<Led>() {
            @Override public int compare(Led a, Led b) { return a.channel - b.channel; }
        });

        synchronized (LedController.this) {
            rootOk = true;
            rootError = "";
            allLedNames = extractAllNames(out);
            for (Led old : leds) savedLedState.put(old.path, new int[]{ old.enabled ? 1 : 0, old.percent });
            leds.clear();
            leds.addAll(parsed);
            for (Led l : leds) {
                int[] saved = savedLedState.get(l.path);
                if (saved != null) {
                    l.enabled = saved[0] != 0;
                    l.percent = Math.max(0, Math.min(100, saved[1]));
                } else {
                    l.enabled = true;
                    l.percent = masterPercent;
                }
            }
            discovering = false;
        }

        postBusy(false, getLeds().size() + " LED");
        postLedsChanged();

        boolean cleanup;
        synchronized (this) {
            cleanup = pendingCleanup;
            pendingCleanup = false;
        }
        if (cleanup || isOn()) applyNow();
    }

    public synchronized void requireCleanupOnDiscover() { pendingCleanup = true; }

    private static String extractAllNames(String out) {
        for (String line : out.split("\n")) {
            if (line.startsWith("@ALL|")) return line.substring(5).replace(",", ", ");
        }
        return "";
    }

    private List<Led> parseDiscovery(String out) {
        List<SwitchInfo> switches = new ArrayList<>();
        List<Led> torches = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("@SWITCH|")) {
                String[] p = line.split("\\|", 6);
                if (p.length >= 6) {
                    SwitchInfo sw = new SwitchInfo();
                    sw.index = parseInt(p[1], -1);
                    sw.mask = parseMask(p[2]);
                    sw.path = p[3]; sw.name = p[4]; sw.maxCurrent = parseInt(p[5], 0);
                    switches.add(sw);
                }
            } else if (line.startsWith("@TORCH|")) {
                String[] p = line.split("\\|", 5);
                if (p.length >= 5) {
                    Led led = new Led();
                    led.path = p[1]; led.name = p[2]; led.channel = parseInt(p[3], -1); led.max = parseInt(p[4], 0);
                    if (led.channel >= 0) torches.add(led);
                }
            }
        }

        List<Led> result = new ArrayList<>();
        boolean masksKnown = false;
        for (SwitchInfo sw : switches) {
            if (sw.mask != 0) { masksKnown = true; break; }
        }
        for (Led led : torches) {
            long bit = 1L << led.channel;
            SwitchInfo best = null;
            for (SwitchInfo sw : switches) {
                if ((sw.mask & bit) == 0) continue;
                if (best == null || Long.bitCount(sw.mask) < Long.bitCount(best.mask)) best = sw;
            }
            if (best == null && !masksKnown) {
                for (SwitchInfo sw : switches) {
                    if (sw.index == led.channel) { best = sw; break; }
                }
            }
            if (best == null) continue;
            led.switchPath = best.path; led.switchName = best.name; led.maxSwitchCurrent = best.maxCurrent;
            if (led.max <= 0) led.max = 500;
            result.add(led);
        }
        return result;
    }

    private static final class SwitchInfo { int index; long mask; String path = ""; String name = ""; int maxCurrent; }

    private static long parseMask(String hex) {
        try { return Long.parseLong(hex.trim(), 16); } catch (Exception e) { return 0L; }
    }

    private static int parseInt(String s, int def) {
        try {
            s = s.trim();
            if (s.startsWith("0x")) return (int) Long.parseLong(s.substring(2), 16);
            return Integer.parseInt(s);
        } catch (Exception e) { return def; }
    }

    public synchronized void setOn(boolean value) {
        if (value) {
            if (autoHook) capture = true;
            else if (!capture) { on = false; savePrefsLocked(); return; }
        }
        on = value;
        savePrefsLocked();
        applyNow();
    }

    public synchronized void setAutoOffMin(int value) { autoOffMin = value; savePrefsLocked(); }
    public synchronized void setKeepScreenOn(boolean value) { keepScreenOn = value; savePrefsLocked(); }
    public synchronized boolean isSmoothUp() { return smoothUp; }
    public synchronized void setSmoothUp(boolean value) { smoothUp = value; savePrefsLocked(); }
    public synchronized boolean isSmoothDown() { return smoothDown; }
    public synchronized void setSmoothDown(boolean value) { smoothDown = value; savePrefsLocked(); }
    public synchronized int getSmoothMs() { return smoothMs; }
    public synchronized void setSmoothMs(int value) { smoothMs = Math.max(100, Math.min(5000, value)); savePrefsLocked(); }
    public synchronized boolean isCapture() { return capture; }
    
    public synchronized void setCapture(boolean value) {
        capture = value;
        if (!value) on = false;
        savePrefsLocked();
        applyNow();
    }

    public synchronized boolean isWarnDismissed() { return warnDismissed; }
    public synchronized void setWarnDismissed(boolean value) { warnDismissed = value; savePrefsLocked(); }
    public synchronized int getTilePercent() { return tilePercent; }
    public synchronized void setTilePercent(int value) { tilePercent = Math.max(5, Math.min(100, value)); savePrefsLocked(); }
    public synchronized int getTileTemp() { return tileTemp; }
    public synchronized void setTileTemp(int value) { tileTemp = Math.max(0, Math.min(100, value)); savePrefsLocked(); }
    public synchronized boolean isTileBoost() { return tileBoost; }
    public synchronized void setTileBoost(boolean value) { tileBoost = value; savePrefsLocked(); }
    
    public synchronized boolean isTileTempMode() { return tileTempMode; }
    public synchronized void setTileTempMode(boolean value) { tileTempMode = value; savePrefsLocked(); }

    public synchronized void setAutoHook(boolean value) {
        autoHook = value;
        if (!value) capture = true;
        savePrefsLocked();
    }

    public synchronized void setUnlockedMax(boolean value) { unlockedMax = value; savePrefsLocked(); }
    public synchronized void setSnapToEdges(boolean value) { snapToEdges = value; savePrefsLocked(); }
    public synchronized void setStockWarnAck(boolean value) { stockWarnAck = value; savePrefsLocked(); }
    
    public synchronized void setTempMode(boolean value) { 
        tempMode = value; 
        savePrefsLocked(); 
        if (on) applyNow();
    }

    public synchronized void setMasterPercent(int value) {
        masterPercent = Math.max(0, Math.min(100, value));
        for (Led l : leds) { l.percent = masterPercent; if (masterPercent > 0) l.enabled = true; }
        savePrefsLocked();
        if (on) apply();
    }

    public synchronized void setTempPercent(int value) {
        tempPercent = Math.max(0, Math.min(100, value));
        savePrefsLocked();
        if (on) apply();
    }

    public synchronized void setLedEnabled(String path, boolean enabled) {
        for (Led l : leds) { if (l.path.equals(path)) { l.enabled = enabled; break; } }
        savePrefsLocked();
        if (on) applyNow();
    }

    public synchronized void setLedPercent(String path, int percent) {
        percent = Math.max(0, Math.min(100, percent));
        for (Led l : leds) {
            if (l.path.equals(path)) { l.percent = percent; if (percent > 0) l.enabled = true; break; }
        }
        savePrefsLocked();
        if (on) apply();
    }

    public synchronized void preset(boolean boostValue, String methodValue, boolean all, int percent, String onlyPath, boolean onValue) {
        boost = boostValue; masterPercent = Math.max(0, Math.min(100, percent));
        for (Led l : leds) { l.enabled = all || (onlyPath != null && l.path.equals(onlyPath)); l.percent = masterPercent; }
        on = onValue;
        if (autoHook) capture = onValue;
        else if (onValue) capture = true; 
        savePrefsLocked();
        applyNow();
    }

    public synchronized void tileToggle() {
        if (on) {
            on = false;
            if (autoHook) capture = false;
            savePrefsLocked();
            applyNow();
            return;
        }
        if (!capture) {
            if (!autoHook) return;
            capture = true;
        }
        int p = tileBoost ? 100 : tilePercent;
        boost = tileBoost; masterPercent = p; tempPercent = tileTemp; tempMode = tileTempMode;
        for (Led l : leds) { l.enabled = true; l.percent = p; }
        on = true;
        savePrefsLocked();
        applyNow();
    }

    public void execRaw(final String command, final RawCallback callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String out = RootShell.get().exec(command, 15000);
                if (out == null) out = "ERROR: " + RootShell.get().getLastError();
                synchronized (LedController.this) { lastCommand = command + "\n"; lastCommandOutput = out; }
                final String result = out;
                if (callback != null) main.post(new Runnable() { @Override public void run() { callback.onResult(result); } });
            }
        });
    }

    public void apply() {
        synchronized (this) {
            if (applying) { pendingApply = true; return; }
        }
        scheduleApply(0);
    }

    public void applyNow() {
        main.removeCallbacks(applyRunnable);
        synchronized (this) {
            if (applying) { pendingApply = true; return; }
        }
        main.post(new Runnable() { @Override public void run() { applyRunnable.run(); } });
    }

    private void doApply() {
        List<Led> snapshot;
        boolean onValue, smoothUpOn, smoothDownOn, captureOn, wasOn, unlocked, useTemp, autoHookOn;
        int durationMs, temp;
        synchronized (this) {
            snapshot = getLeds(); onValue = on; smoothUpOn = smoothUp; smoothDownOn = smoothDown;
            captureOn = capture; wasOn = appliedOn; durationMs = smoothMs; temp = tempPercent; unlocked = unlockedMax;
            useTemp = tempMode; autoHookOn = autoHook;
        }
        if (snapshot.isEmpty()) return;

        RootShell sh = RootShell.get();
        if (!sh.ensureStarted()) { postMessage("Нет root: " + sh.getLastError()); return; }

        StringBuilder script = new StringBuilder();
        
        if (unlocked) {
            for (int i = 0; i < 4; i++) {
                script.append("echo 750000 > /sys/class/leds/led:switch_").append(i).append("/max_current 2>/dev/null\n");
                script.append("echo 750 > /sys/class/leds/led:torch_").append(i).append("/max_brightness 2>/dev/null\n");
            }
        }

        if (!captureOn) {
            if (wasOn || onValue) {
                appendReleaseAll(script);
                sh.exec(script.toString(), 15000); 
                
                boolean stillOff;
                synchronized (this) { stillOff = !on; }
                if (stillOff) sh.exec("stop qcamerasrv 2>/dev/null; sleep 0.5; start qcamerasrv 2>/dev/null", 10000);
            }
            synchronized (this) { appliedOn = false; }
            return;
        }

        boolean rampUp = onValue && smoothUpOn && durationMs > 0 && onValue != wasOn;
        boolean rampDown = !onValue && smoothDownOn && durationMs > 0 && onValue != wasOn;
        boolean ramp = rampUp || rampDown;

        List<Led> activeLeds = new ArrayList<>();
        for (Led l : snapshot) {
            if (!l.enabled) continue;
            int actualPercent;
            if (useTemp && snapshot.size() == 2) {
                boolean isCold = (l.channel % 2 == 1);
                double mix = isCold ? (temp / 100.0) : ((100 - temp) / 100.0);
                actualPercent = (int) Math.round(l.percent * mix);
            } else {
                actualPercent = l.percent;
            }
            
            if (actualPercent > 0) {
                Led a = new Led();
                a.path = l.path; a.switchPath = l.switchPath; a.max = unlocked ? 750 : l.max;
                a.percent = actualPercent; a.enabled = true;
                activeLeds.add(a);
            } else {
                appendOff(script, l);
            }
        }

        if (ramp) {
            if (rampUp) appendRampUp(script, activeLeds, durationMs);
            else appendRampDown(script, activeLeds, durationMs);
        } else if (!onValue) {
            appendReleaseAll(script);
        } else {
            for (Led l : activeLeds) { appendOn(script, l, l.rawValue()); }
        }

        int timeout = ramp ? durationMs + 15000 : 8000;
        String out = sh.exec(script.toString(), timeout);
        
        synchronized (this) {
            appliedOn = onValue;
            lastCommand = script.toString();
            lastCommandOutput = out == null ? ("ERROR: " + sh.getLastError()) : out;
            if (out == null) { rootOk = sh.isRootOk(); rootError = sh.getLastError(); }
        }
        
        if (out == null) {
            long now = System.currentTimeMillis();
            if (now - lastErrorAt > 1500L) { lastErrorAt = now; postMessage("Команда root: " + sh.getLastError()); }
        }

        if (!onValue && autoHookOn && captureOn) {
            boolean stillOff;
            synchronized (this) {
                stillOff = !on;
                if (stillOff) { capture = false; savePrefsLocked(); }
            }
            if (stillOff) sh.exec("stop qcamerasrv 2>/dev/null; sleep 0.5; start qcamerasrv 2>/dev/null", 10000);
        }
    }

    private static void appendRampUp(StringBuilder b, List<Led> leds, int durationMs) {
        if (leds.isEmpty()) return;
        int steps = Math.max(2, Math.min(100, Math.round(durationMs / 50f)));
        String delay = String.format(Locale.US, "%.3f", Math.max(0.02, (durationMs / (double) steps) / 1000.0));
        for (Led l : leds) {
            b.append("echo 1 > ").append(RootShell.quote(l.path)).append("/brightness\n");
            if (l.switchPath.length() > 0) b.append("echo 1 > ").append(RootShell.quote(l.switchPath)).append("/brightness\n");
        }
        b.append("i=1\nwhile [ $i -le ").append(steps).append(" ]; do\n");
        for (Led l : leds) b.append("  echo $((i*").append(l.rawValue()).append('/').append(steps).append(")) > ").append(RootShell.quote(l.path)).append("/brightness\n");
        b.append("  i=$((i+1))\nsleep ").append(delay).append("\ndone\n");
        for (Led l : leds) b.append("echo ").append(l.rawValue()).append(" > ").append(RootShell.quote(l.path)).append("/brightness\n");
    }

    private static void appendRampDown(StringBuilder b, List<Led> leds, int durationMs) {
        if (leds.isEmpty()) { appendReleaseAll(b); return; }
        int steps = Math.max(2, Math.min(100, Math.round(durationMs / 50f)));
        String delay = String.format(Locale.US, "%.3f", Math.max(0.02, (durationMs / (double) steps) / 1000.0));
        b.append("i=").append(steps).append("\nwhile [ $i -ge 1 ]; do\n");
        for (Led l : leds) b.append("  echo $((i*").append(l.rawValue()).append('/').append(steps).append(")) > ").append(RootShell.quote(l.path)).append("/brightness\n");
        b.append("  i=$((i-1))\nsleep ").append(delay).append("\ndone\n");
        appendReleaseAll(b);
    }

    private static void appendReleaseAll(StringBuilder b) {
        for (int i = 0; i < 4; i++) {
            b.append("echo 0 > \"/sys/class/leds/led:switch_").append(i).append("/brightness\" 2>/dev/null\n");
            b.append("echo 0 > \"/sys/class/leds/led:torch_").append(i).append("/brightness\" 2>/dev/null\n");
            b.append("echo 0 > \"/sys/class/leds/led:flash_").append(i).append("/brightness\" 2>/dev/null\n");
            b.append("echo 0 > \"/sys/class/leds/led:switch_").append(i).append("/max_current\" 2>/dev/null\n");
        }
    }

    private static void appendOn(StringBuilder b, Led l, int value) {
        String p = RootShell.quote(l.path);
        b.append("echo ").append(value).append(" > ").append(p).append("/brightness\n");
        if (l.switchPath.length() > 0) b.append("echo 1 > ").append(RootShell.quote(l.switchPath)).append("/brightness\n");
    }

    private static void appendOff(StringBuilder b, Led l) {
        if (l.switchPath.length() > 0) b.append("echo 0 > ").append(RootShell.quote(l.switchPath)).append("/brightness\n");
        b.append("echo 0 > ").append(RootShell.quote(l.path)).append("/brightness\n");
    }

    private synchronized void loadPrefs() {
        try {
            String json = prefs.getString("state", "");
            if (json == null || json.length() == 0) return;
            JSONObject o = new JSONObject(json);
            on = o.optBoolean("on", false); boost = o.optBoolean("boost", false); keepScreenOn = o.optBoolean("keepScreen", false);
            masterPercent = o.optInt("master", 30); tempPercent = o.optInt("temp", 50); autoOffMin = o.optInt("autoOff", 0);
            smoothUp = o.optBoolean("smoothUp", false); smoothDown = o.optBoolean("smoothDown", false); smoothMs = o.optInt("smoothMs", 700);
            capture = o.optBoolean("capture", true); autoHook = o.optBoolean("autoHook", true); unlockedMax = o.optBoolean("unlockedMax", false);
            tempMode = o.optBoolean("tempMode", false);
            warnDismissed = o.optBoolean("warnOff", false); tilePercent = o.optInt("tilePercent", 30); tileTemp = o.optInt("tileTemp", 50);
            tileBoost = o.optBoolean("tileBoost", false); tileTempMode = o.optBoolean("tileTempMode", false);
            snapToEdges = o.optBoolean("snapToEdges", true); stockWarnAck = o.optBoolean("stockWarnAck", false);
            JSONArray arr = o.optJSONArray("leds");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.getJSONObject(i);
                    String path = l.optString("path", "");
                    if (path.length() > 0) savedLedState.put(path, new int[]{ l.optBoolean("enabled", true) ? 1 : 0, l.optInt("percent", 30) });
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void savePrefsLocked() {
        if (prefs == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("on", on); o.put("boost", boost); o.put("keepScreen", keepScreenOn); o.put("master", masterPercent);
            o.put("temp", tempPercent); o.put("autoOff", autoOffMin); o.put("smoothUp", smoothUp); o.put("smoothDown", smoothDown);
            o.put("smoothMs", smoothMs); o.put("capture", capture); o.put("autoHook", autoHook); o.put("unlockedMax", unlockedMax);
            o.put("tempMode", tempMode);
            o.put("warnOff", warnDismissed); o.put("tilePercent", tilePercent); o.put("tileTemp", tileTemp); o.put("tileBoost", tileBoost); o.put("tileTempMode", tileTempMode);
            o.put("snapToEdges", snapToEdges); o.put("stockWarnAck", stockWarnAck);
            JSONArray arr = new JSONArray();
            for (Led l : leds) {
                JSONObject lo = new JSONObject();
                lo.put("path", l.path); lo.put("enabled", l.enabled); lo.put("percent", l.percent);
                arr.put(lo);
            }
            o.put("leds", arr);
            prefs.edit().putString("state", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void postBusy(final boolean busy, final String status) {
        final Listener l = listener;
        if (l != null) main.post(new Runnable() { @Override public void run() { l.onBusy(busy, status); } });
    }

    private void postLedsChanged() {
        final Listener l = listener;
        if (l != null) main.post(new Runnable() { @Override public void run() { l.onLedsChanged(); } });
    }

    private void postMessage(final String message) {
        final Listener l = listener;
        if (l != null) main.post(new Runnable() { @Override public void run() { l.onMessage(message); } });
    }
}