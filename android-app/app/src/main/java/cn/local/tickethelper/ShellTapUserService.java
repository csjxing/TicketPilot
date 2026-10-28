package cn.local.tickethelper;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import androidx.annotation.Keep;
import cn.local.tickethelper.core.ShellTapPolicy;
import cn.local.tickethelper.core.DetailScrollPolicy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Shizuku UserService, shell UID only; exposes one bounded tap, never a general shell. */
@Keep
public final class ShellTapUserService extends IShellTapService.Stub {
    private static final String OWN_PACKAGE = "cn.local.tickethelper";
    private final Context context;
    private final int ownerUid;
    private final Object lock = new Object();
    private final ExecutorService guardWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ticket-dispatch-guard"); thread.setDaemon(true); return thread;
    });
    private long session, generation;
    private boolean busy, destroyed;
    private Process activeProcess;

    @Keep public ShellTapUserService(Context context) {
        if (context == null || !OWN_PACKAGE.equals(context.getPackageName()))
            throw new SecurityException("invalid_service_context");
        this.context = context;
        int uid;
        try { uid = context.getPackageManager().getApplicationInfo(OWN_PACKAGE, 0).uid; }
        catch (Exception e) { throw new SecurityException("owner_unavailable"); }
        if (uid < 10000 || uid >= 100000) throw new SecurityException("unsupported_owner_profile");
        ownerUid = uid;
        if (android.os.Process.myUid() != 2000) throw new SecurityException("shell_uid_required");
    }

    private void enforceOwner() {
        if (android.os.Process.myUid() != 2000 || Binder.getCallingUid() != ownerUid)
            throw new SecurityException("caller_not_owner");
    }

    @Override public boolean initialize(long token, long revision) {
        enforceOwner();
        synchronized (lock) {
            if (destroyed || busy || token <= 0 || revision <= 0) return false;
            // A service instance has exactly one client session. Reconnect creates a new instance.
            if (session != 0 && session != token) return false;
            session = token; generation = revision;
            return true;
        }
    }

    @Override public boolean ping(long token) {
        enforceOwner();
        synchronized (lock) { return !destroyed && token != 0 && session == token; }
    }

    @Override public void cancel(long token, long nextGeneration) {
        enforceOwner();
        synchronized (lock) {
            if (token == 0 || session != token || nextGeneration <= generation) return;
            generation = nextGeneration;
            if (activeProcess != null) activeProcess.destroyForcibly();
        }
    }

    @Override public String tap(long token, long revision, int x, int y, long deadline, IDispatchGuard guard) {
        return dispatch(token, revision, x, y, null, deadline, guard);
    }

    @Override public String scrollDetailUp(long token, long revision, int left, int top, int right, int bottom,
                                         long deadline, IDispatchGuard guard) {
        return dispatch(token, revision, 0, 0, new int[]{left, top, right, bottom}, deadline, guard);
    }

    private String dispatch(long token, long revision, int x, int y, int[] scrollFrame, long deadline, IDispatchGuard guard) {
        enforceOwner();
        if (guard == null) return "dispatch_guard_required";
        long start = SystemClock.elapsedRealtime();
        if (deadline <= start || deadline - start > ShellTapPolicy.MAX_FUTURE_MS) return "stale_deadline";
        final long end = Math.min(deadline, start + ShellTapPolicy.MAX_EXECUTION_MS);
        synchronized (lock) {
            if (!current(token, revision)) return "cancelled";
            if (busy) return "busy";
            busy = true;
        }
        boolean inputStarted = false;
        try {
            if (!screenSafe()) return "screen_locked_or_off";
            DisplayMetrics metrics = displayMetrics();
            DetailScrollPolicy.Plan scroll = scrollFrame == null ? null : DetailScrollPolicy.forRoot(
                    scrollFrame[0], scrollFrame[1], scrollFrame[2], scrollFrame[3], metrics.widthPixels, metrics.heightPixels);
            if (scrollFrame != null && scroll == null) return "invalid_detail_frame";
            int startX = scroll == null ? x : scroll.x, startY = scroll == null ? y : scroll.startY;
            String invalid = ShellTapPolicy.validate(startX, startY, metrics.widthPixels, metrics.heightPixels,
                    SystemClock.elapsedRealtime(), deadline);
            if (invalid != null) return invalid;
            // Public diagnostic snapshot; discarded immediately and never written to logs or disk.
            CommandResult foreground = execute(new String[]{"/system/bin/dumpsys", "window", "displays"},
                    token, revision, Math.min(end, SystemClock.elapsedRealtime() + 600));
            if (!foreground.completed || foreground.exitCode != 0 || foreground.truncated
                    || !ShellTapPolicy.damaiFocused(foreground.output)) return "foreground_unverified";
            if (!screenSafe()) return "screen_locked_or_off";
            DisplayMetrics latest = displayMetrics();
            if (latest.widthPixels != metrics.widthPixels || latest.heightPixels != metrics.heightPixels)
                return "display_changed";
            invalid = ShellTapPolicy.validate(startX, startY, latest.widthPixels, latest.heightPixels,
                    SystemClock.elapsedRealtime(), end);
            if (invalid != null) return invalid;
            if (scroll != null) {
                invalid = ShellTapPolicy.validate(scroll.x, scroll.endY, latest.widthPixels, latest.heightPixels,
                        SystemClock.elapsedRealtime(), end);
                if (invalid != null) return invalid;
            }
            // The application checks the exact captured node/page now, after the asynchronous
            // system snapshot. Its callback is single-use and bounded by the same deadline.
            if (!allowDispatch(guard, end)) return "dispatch_guard_rejected";
            synchronized (lock) {
                if (!current(token, revision)) return "cancelled";
                if (SystemClock.elapsedRealtime() >= end) return "stale_deadline";
                // Spawn and cancellation share a lock: a canceled queued command cannot start later.
                activeProcess = new ProcessBuilder(scroll == null ? ShellTapPolicy.tapCommand(x, y) : scroll.command())
                        .redirectErrorStream(true).start();
                inputStarted = true;
            }
            CommandResult result = awaitActive(token, revision, end);
            synchronized (lock) {
                if (!current(token, revision)) return "cancelled_outcome_unknown";
            }
            if (!result.completed) return "timeout_outcome_unknown";
            if (result.exitCode != 0) return "input_failed_outcome_unknown";
            // This means the standard input command finished; the caller must verify the UI transition.
            return "command_completed";
        } catch (Exception e) {
            return inputStarted ? "service_error_outcome_unknown" : "service_guard_failed";
        } finally {
            synchronized (lock) {
                if (activeProcess != null) { activeProcess.destroyForcibly(); activeProcess = null; }
                busy = false;
            }
        }
    }

    private boolean current(long token, long revision) {
        return !destroyed && session != 0 && token == session && revision == generation;
    }

    private boolean allowDispatch(IDispatchGuard guard, long end) {
        long remaining = end - SystemClock.elapsedRealtime();
        if (remaining <= 0) return false;
        // Bound the RPC itself too: a suspended application or exhausted binder pool must not
        // keep a future input alive. A late callback can only return a Boolean; it cannot tap.
        Future<Boolean> result = guardWorker.submit(guard::allow);
        try { return Boolean.TRUE.equals(result.get(Math.min(700, remaining), TimeUnit.MILLISECONDS)); }
        catch (Exception e) { result.cancel(true); return false; }
    }

    private boolean screenSafe() {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && keyguard != null && power.isInteractive()
                && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    @SuppressWarnings("deprecation") private DisplayMetrics displayMetrics() {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null || display.getState() != Display.STATE_ON) throw new IllegalStateException("display_unavailable");
        DisplayMetrics result = new DisplayMetrics(); display.getRealMetrics(result);
        return result;
    }

    private CommandResult execute(String[] command, long token, long revision, long end) throws Exception {
        synchronized (lock) {
            if (!current(token, revision) || SystemClock.elapsedRealtime() >= end)
                return new CommandResult(false, -1, "", false);
            activeProcess = new ProcessBuilder(command).redirectErrorStream(true).start();
        }
        return awaitActive(token, revision, end);
    }

    private CommandResult awaitActive(long token, long revision, long end) throws Exception {
        final Process process;
        synchronized (lock) { process = activeProcess; }
        if (process == null) return new CommandResult(false, -1, "", false);
        final Capture capture = new Capture(process.getInputStream());
        Thread reader = new Thread(capture, "ticket-input-output"); reader.setDaemon(true); reader.start();
        boolean completed = process.waitFor(Math.max(1, end - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        if (!completed) process.destroyForcibly();
        reader.join(Math.max(1, Math.min(40, end - SystemClock.elapsedRealtime())));
        if (reader.isAlive()) { process.getInputStream().close(); capture.truncated = true; }
        synchronized (lock) {
            if (!current(token, revision)) completed = false;
            if (activeProcess == process) activeProcess = null;
        }
        return new CommandResult(completed, completed ? process.exitValue() : -1,
                capture.text(), capture.truncated);
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile boolean truncated;
        Capture(InputStream input) { this.input = input; }
        @Override public void run() {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = stream.read(buffer)) != -1) {
                    synchronized (bytes) {
                        if (bytes.size() + n <= 262144) bytes.write(buffer, 0, n);
                        else truncated = true;
                    }
                }
            } catch (Exception e) { truncated = true; }
        }
        String text() { synchronized (bytes) { return new String(bytes.toByteArray(), StandardCharsets.UTF_8); } }
    }

    private static final class CommandResult {
        final boolean completed, truncated;
        final int exitCode;
        final String output;
        CommandResult(boolean completed, int exitCode, String output, boolean truncated) {
            this.completed = completed; this.exitCode = exitCode; this.output = output; this.truncated = truncated;
        }
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (android.os.Process.myUid() != 2000 || (caller != ownerUid && caller != 2000))
            throw new SecurityException("caller_not_owner_or_shizuku");
        synchronized (lock) {
            destroyed = true; generation++;
            if (activeProcess != null) activeProcess.destroyForcibly();
        }
        guardWorker.shutdownNow();
        System.exit(0);
    }
}
