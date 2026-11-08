package ru.cuxtoms.flashx;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A tiny persistent "su" shell. Keeping one process alive makes slider updates
 * instant and, more importantly, lets us notice when root access is revoked.
 */
public final class RootShell {

    private static final RootShell INSTANCE = new RootShell();

    private final Object lock = new Object();
    private Process process;
    private BufferedWriter stdin;
    private BlockingQueue<String> stdout;
    private Thread reader;
    private int seq;
    private boolean rootOk;
    private String lastError = "";
    private String suCommand = "su";

    private static final String[][] SU_CANDIDATES = {
            {"su"},
            {"/system/bin/su"},
            {"/debug_ramdisk/su"},
            {"/data/adb/ksu/bin/su"},
            {"/data/adb/ksud", "su"},
            {"/data/user_de/0/df.root/ksud", "su"}
    };

    private RootShell() {
    }

    public static RootShell get() {
        return INSTANCE;
    }

    public boolean isRootOk() {
        synchronized (lock) {
            return rootOk && process != null && process.isAlive();
        }
    }

    public String getLastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /** Starts (or restarts) the root shell. Must be called on a worker thread. */
    public boolean ensureStarted() {
        synchronized (lock) {
            if (rootOk && process != null && process.isAlive()) {
                return true;
            }
            return startLocked();
        }
    }

    private boolean startLocked() {
        closeLocked();
        lastError = "";
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < SU_CANDIDATES.length; i++) {
            String[] cmd = SU_CANDIDATES[i];
            int timeout = i == 0 ? 12000 : 5000;
            if (tryCandidate(cmd, timeout, errors)) {
                suCommand = join(cmd);
                rootOk = true;
                lastError = "";
                Log.i("FlashX", "root shell started: " + suCommand);
                return true;
            }
        }
        lastError = errors.toString().trim();
        if (lastError.length() == 0) {
            lastError = "su не найден";
        }
        Log.w("FlashX", "root shell failed: " + lastError);
        return false;
    }

    private boolean tryCandidate(String[] cmd, int timeoutMs, StringBuilder errors) {
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            errors.append(join(cmd)).append(": ").append(e.getMessage()).append('\n');
            return false;
        }

        try {
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            final BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));
            final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
            stdout = queue;
            reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = out.readLine()) != null) {
                            queue.offer(line);
                        }
                    } catch (IOException ignored) {
                        // The process died; the queue gets an EOF marker below.
                    }
                    queue.offer(EOF);
                }
            }, "flashx-root-reader");
            reader.setDaemon(true);
            reader.start();

            String token = "FXR" + System.nanoTime();
            writeLocked("id\n");
            writeLocked("echo " + token + "\n");

            StringBuilder handshake = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean found = false;
            while (System.currentTimeMillis() < deadline) {
                String line = stdout.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (EOF.equals(line)) {
                    break;
                }
                if (token.equals(line.trim())) {
                    found = true;
                    break;
                }
                handshake.append(line).append('\n');
            }

            if (!found) {
                errors.append(join(cmd)).append(": нет ответа (")
                        .append(timeoutMs).append(" мс) ")
                        .append(handshake.toString().trim()).append('\n');
                closeLocked();
                return false;
            }
            String hs = handshake.toString();
            if (!hs.contains("uid=0")) {
                errors.append(join(cmd)).append(": не root: ")
                        .append(hs.trim()).append('\n');
                closeLocked();
                return false;
            }
            return true;
        } catch (Exception e) {
            errors.append(join(cmd)).append(": ").append(e).append('\n');
            closeLocked();
            return false;
        }
    }

    private static String join(String[] cmd) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(cmd[i]);
        }
        return b.toString();
    }

    public String getSuCommand() {
        synchronized (lock) {
            return suCommand;
        }
    }

    /**
     * Runs a command in the root shell and returns its combined output.
     * Returns null on timeout / shell failure.
     */
    public String exec(String command, int timeoutMs) {
        synchronized (lock) {
            if (!rootOk || process == null || !process.isAlive()) {
                if (!startLocked()) {
                    return null;
                }
            }
            String token = "FXE" + (seq++);
            try {
                writeLocked(command + "\n");
                writeLocked("echo " + token + ":$?\n");
            } catch (IOException e) {
                lastError = "Не удалось записать в su: " + e;
                Log.w("FlashX", lastError);
                closeLocked();
                return null;
            }

            StringBuilder result = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    lastError = "Команда не завершилась за " + timeoutMs + " мс";
                    return null;
                }
                String line;
                try {
                    line = stdout.poll(left, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastError = "Прервано";
                    return null;
                }
                if (line == null) {
                    lastError = "Таймаут команды";
                    return null;
                }
                if (EOF.equals(line)) {
                    lastError = "Root-shell завершился";
                    closeLocked();
                    return null;
                }
                if (line.startsWith(token + ":")) {
                    break;
                }
                result.append(line).append('\n');
            }
            return result.toString();
        }
    }

    public void reset() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private void writeLocked(String text) throws IOException {
        stdin.write(text);
        stdin.flush();
    }

    private void closeLocked() {
        rootOk = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            process = null;
        }
        if (stdin != null) {
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
            stdin = null;
        }
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        stdout = null;
    }

    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static final String EOF = "__FLASHX_EOF__";
}
