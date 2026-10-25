package cn.local.tickethelper;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.local.tickethelper.core.ShellTapPolicy;
import cn.local.tickethelper.core.InputCancellationPolicy;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** Explicit optional input transport. No implicit permission request, connection, retry or fallback. */
public final class ShizukuInputBridge {
    public interface Callback { void onResult(Result result); }
    /** Read-only page/node validation; called once on main immediately before remote input. */
    public interface DispatchGuard { boolean allow(); }
    public static final class Result {
        /** Success means command completion only, never inventory or order success. */
        public final boolean success, outcomeUnknown;
        public final String code;
        Result(boolean success, boolean outcomeUnknown, String code) {
            this.success = success; this.outcomeUnknown = outcomeUnknown; this.code = code;
        }
    }
    private static ShizukuInputBridge singleton;
    public static synchronized ShizukuInputBridge get(Context context) {
        if (singleton == null) singleton = new ShizukuInputBridge(context.getApplicationContext());
        return singleton;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService inputWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService controlWorker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private volatile Binding binding;
    private volatile Pending pending;
    private volatile long generation = 1;
    private volatile boolean ready, cancelling;
    private volatile String lastFailure = "";

    private ShizukuInputBridge(Context context) {
        this.context = context;
        // A later binder arrival deliberately does not reconnect or resume a monitor.
        Shizuku.addBinderDeadListener(() -> {
            lastFailure = "Shizuku 已停止，请手动重新连接。";
            disconnect();
        });
    }

    /** Null means an authorized shell backend is available, not necessarily connected. */
    private String unavailable() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku 未运行或尚未连接；请先在手机启动。";
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return "需要 Shizuku 13 或更新版本。";
            if (Shizuku.getUid() != 2000) return "仅支持调试模式启动的 Shizuku，不使用 root 模式。";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "尚未授权票务助手使用 Shizuku。";
            return null;
        } catch (RuntimeException e) { return "Shizuku 暂不可用，请在手机检查运行状态。"; }
    }

    public String status() {
        String problem = unavailable();
        if (problem != null) return problem;
        if (cancelling) return "正在取消未完成操作。";
        if (isReady()) return "Shizuku 已连接；点击结果仍需页面验证。";
        if (binding != null) return "正在连接 Shizuku 输入服务。";
        return lastFailure.isEmpty() ? "Shizuku 已授权；请主动点击连接。" : lastFailure;
    }

    public boolean isReady() {
        Binding current = binding;
        return ready && !cancelling && unavailable() == null && current != null
                && current.remote != null && current.remote.asBinder().pingBinder();
    }

    /** Must only be called by a visible Activity's explicit user button. Does not connect. */
    public void requestPermission(Activity activity, int requestCode) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity == null || activity.isFinishing()
                || activity.isDestroyed() || !activity.hasWindowFocus() || !monitorPaused())
            throw new IllegalStateException("请先暂停任务，再在当前页面授权。");
        if (!Shizuku.pingBinder()) throw new IllegalStateException("请先在手机启动 Shizuku。");
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000)
            throw new IllegalStateException("需要以调试模式启动 Shizuku 13 或更新版本。");
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return;
        if (Shizuku.shouldShowRequestPermissionRationale())
            throw new IllegalStateException("请在 Shizuku 的应用管理中手动允许票务助手。");
        Shizuku.requestPermission(requestCode);
    }

    private boolean monitorPaused() {
        MonitorService monitor = MonitorService.instance;
        return !new AppStore(context).isArmed() && (monitor == null || !monitor.isRunning());
    }

    /** Called only by a deliberate user action while paused. A timeout never retries. */
    public void connect(Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> connect(callback)); return; }
        if (!monitorPaused()) { reply(callback, failure("monitor_must_be_paused")); return; }
        String unavailable = unavailable();
        if (unavailable != null) { lastFailure = unavailable; reply(callback, failure("shizuku_unavailable")); return; }
        if (isReady()) { reply(callback, new Result(true, false, "connected")); return; }
        disconnect(); lastFailure = "";
        long token;
        do { token = random.nextLong() & Long.MAX_VALUE; } while (token == 0);
        final Binding attempt = new Binding(token, generation, callback);
        binding = attempt;
        try { Shizuku.bindUserService(attempt.args, attempt.connection); }
        catch (RuntimeException e) { connectFailed(attempt, "connect_failed"); return; }
        main.postDelayed(() -> {
            if (binding == attempt && !ready) connectFailed(attempt, "connect_timeout");
        }, 6000);
    }

    private final class Binding {
        final long token, revision;
        final Callback callback;
        final AtomicBoolean delivered = new AtomicBoolean();
        final Shizuku.UserServiceArgs args;
        final ServiceConnection connection;
        volatile IShellTapService remote;
        Binding(long token, long revision, Callback callback) {
            this.token = token; this.revision = revision; this.callback = callback;
            // A fresh tag prevents a delayed unbind of an old session from killing a new session.
            args = new Shizuku.UserServiceArgs(new ComponentName(context, ShellTapUserService.class))
                    .daemon(false).tag("ticket-tap-" + token).processNameSuffix("ticket_input")
                    .version(1).debuggable(false);
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    if (binding != Binding.this || !service.pingBinder()) { remove(Binding.this); return; }
                    remote = IShellTapService.Stub.asInterface(service);
                    controlWorker.execute(() -> {
                        boolean accepted;
                        try { accepted = remote.initialize(token, revision) && remote.ping(token); }
                        catch (Exception e) { accepted = false; }
                        final boolean initialized = accepted;
                        main.post(() -> {
                            if (binding != Binding.this) { remove(Binding.this); return; }
                            if (!initialized || !monitorPaused() || generation != revision || unavailable() != null) {
                                connectFailed(Binding.this, "service_handshake_failed"); return;
                            }
                            ready = true; complete(new Result(true, false, "connected"));
                        });
                    });
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    main.post(() -> { if (binding == Binding.this) { lastFailure = "输入服务已断开，请手动重新连接。"; disconnect(); } });
                }
            };
        }
        void complete(Result result) { if (delivered.compareAndSet(false, true)) reply(callback, result); }
    }

    private void connectFailed(Binding attempt, String code) {
        if (binding != attempt) return;
        lastFailure = "输入服务连接失败，请检查 Shizuku 后手动重试。";
        attempt.complete(failure(code)); disconnect();
    }

    /** Invalidates both locally queued and remote queued operations before disconnecting. */
    public void disconnect() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::disconnect); return; }
        ready = false; cancelling = false; generation++;
        Binding old = binding; binding = null;
        Pending work = pending; pending = null;
        if (work != null) work.finish(new Result(false, work.sent, work.sent ? "cancelled_outcome_unknown" : "cancelled"));
        if (old != null) {
            old.complete(failure("connection_cancelled"));
            remove(old);
        }
    }

    private void remove(Binding old) {
        controlWorker.execute(() -> {
            try { if (old.remote != null) old.remote.cancel(old.token, Long.MAX_VALUE); }
            catch (Exception ignored) { /* No retry; unbind removes this exact session. */ }
            try { Shizuku.unbindUserService(old.args, old.connection, true); }
            catch (RuntimeException ignored) { /* Shizuku death is already represented as unavailable. */ }
        });
    }

    /** Stops the current operation without restarting or reissuing it. */
    public void cancelPending() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::cancelPending); return; }
        boolean hasPendingInput = pending != null;
        // pending is installed before worker dispatch, and normal completion clears it only after
        // the remote tap returns. Unknown outcomes disconnect. Thus an idle, ready connection has
        // no queued/remote input to cancel; preserving it avoids making a fresh Start reject itself.
        InputCancellationPolicy.Action action = InputCancellationPolicy.decide(hasPendingInput, cancelling,
                !hasPendingInput && !cancelling && isReady());
        if (action != InputCancellationPolicy.Action.INVALIDATE_AND_CANCEL) return;
        final long revision = ++generation;
        Binding current = binding;
        Pending work = pending; pending = null;
        if (work != null) work.finish(new Result(false, work.sent, work.sent ? "cancelled_outcome_unknown" : "cancelled"));
        if (current == null || current.remote == null) {
            if (current != null) disconnect();
            return;
        }
        cancelling = true; ready = false;
        controlWorker.execute(() -> {
            boolean accepted;
            try { current.remote.cancel(current.token, revision); accepted = current.remote.ping(current.token); }
            catch (Exception e) { accepted = false; }
            final boolean completed = accepted;
            main.post(() -> {
                if (binding != current || generation != revision) return;
                cancelling = false;
                if (completed && unavailable() == null) ready = true;
                else { lastFailure = "取消操作后连接失效，请手动重新连接。"; disconnect(); }
            });
        });
    }

    /** Unguarded input is deliberately unavailable, including to older call sites. */
    public void tap(int x, int y, long deadlineElapsedRealtime, Callback callback) {
        reply(callback, failure("dispatch_guard_required"));
    }

    /** One standard tap. deadline uses SystemClock.elapsedRealtime(), never wall time. */
    public void tap(int x, int y, long deadlineElapsedRealtime, DispatchGuard guard, Callback callback) {
        tapInternal(x, y, deadlineElapsedRealtime, guard, callback, false, false);
    }

    /** User-initiated catalog navigation while monitoring is paused. Never arms a monitor. */
    public void tapCatalog(int x, int y, long deadlineElapsedRealtime, DispatchGuard guard, Callback callback) {
        tapInternal(x, y, deadlineElapsedRealtime, guard, callback, true, false);
    }

    /** Same one-shot dispatch; permits a bounded native checkout re-read in the final guard. */
    public void tapOrder(int x, int y, long deadlineElapsedRealtime, DispatchGuard guard, Callback callback) {
        tapInternal(x, y, deadlineElapsedRealtime, guard, callback, false, true);
    }

    private void tapInternal(int x, int y, long deadlineElapsedRealtime, DispatchGuard guard,
                             Callback callback, boolean catalog, boolean order) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> tapInternal(x, y, deadlineElapsedRealtime, guard, callback, catalog, order)); return;
        }
        if (guard == null) { reply(callback, failure("dispatch_guard_required")); return; }
        long now = SystemClock.elapsedRealtime();
        if (deadlineElapsedRealtime <= now || deadlineElapsedRealtime - now > ShellTapPolicy.MAX_FUTURE_MS) {
            reply(callback, failure("stale_deadline")); return;
        }
        if (!isReady()) { reply(callback, failure("shizuku_unavailable")); return; }
        if (pending != null) { reply(callback, failure("busy")); return; }
        if (!clientScreenSafe(catalog)) { reply(callback, failure("foreground_or_screen_unverified")); return; }
        final Binding current = binding;
        final Pending work = new Pending(generation, callback); pending = work;
        final IDispatchGuard remoteGuard = new IDispatchGuard.Stub() {
            private final AtomicBoolean used = new AtomicBoolean();
            @Override public boolean allow() {
                if (Binder.getCallingUid() != 2000 || !used.compareAndSet(false, true)) return false;
                long remaining = deadlineElapsedRealtime - SystemClock.elapsedRealtime();
                if (remaining <= 0) return false;
                final CountDownLatch done = new CountDownLatch(1);
                final AtomicBoolean active = new AtomicBoolean(true);
                final AtomicBoolean allowed = new AtomicBoolean();
                main.post(() -> {
                    try {
                        if (active.get() && binding == current && generation == work.revision
                                && pending == work && !work.delivered.get() && isReady()
                                && SystemClock.elapsedRealtime() < deadlineElapsedRealtime
                                && clientScreenSafe(catalog)) {
                            allowed.set(guard.allow());
                        }
                    } catch (RuntimeException ignored) { allowed.set(false); }
                    finally { done.countDown(); }
                });
                try {
                    // Native catalog and checkout re-reads remain bounded by the same input deadline.
                    if (!done.await(Math.min(catalog || order ? 600 : 200, remaining), TimeUnit.MILLISECONDS)) return false;
                    return allowed.get() && binding == current && generation == work.revision
                            && pending == work && !work.delivered.get()
                            && SystemClock.elapsedRealtime() < deadlineElapsedRealtime;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return false;
                } finally { active.set(false); }
            }
        };
        inputWorker.execute(() -> {
            if (binding != current || generation != work.revision || pending != work) return;
            if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtime) { complete(work, failure("stale_deadline")); return; }
            String code;
            try {
                work.sent = true;
                code = current.remote.tap(current.token, work.revision, x, y, deadlineElapsedRealtime, remoteGuard);
            } catch (Exception e) { code = "binder_failed_outcome_unknown"; }
            boolean success = "command_completed".equals(code);
            boolean unknown = code == null || code.endsWith("outcome_unknown");
            complete(work, new Result(success, unknown, code == null ? "empty_result_outcome_unknown" : code));
        });
        main.postDelayed(() -> {
            if (pending == work && !work.delivered.get()) {
                work.finish(new Result(false, work.sent, work.sent ? "client_timeout_outcome_unknown" : "client_timeout"));
                disconnect();
            }
        }, Math.min(ShellTapPolicy.MAX_EXECUTION_MS + 200, deadlineElapsedRealtime - now + 200));
    }

    private boolean clientScreenSafe(boolean catalog) {
        try {
            MonitorService monitor = MonitorService.instance;
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (monitor == null || power == null || !power.isInteractive() || keyguard == null
                    || keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()) return false;
            boolean armed = new AppStore(context).isArmed();
            if (catalog ? (monitor.isRunning() || armed || !CatalogController.isActive())
                    : (!monitor.isRunning() || !armed)) return false;
            AccessibilityNodeInfo root = monitor.getRootInActiveWindow();
            if (root == null) return false;
            try { return "cn.damai".contentEquals(root.getPackageName() == null ? "" : root.getPackageName()); }
            finally { root.recycle(); }
        } catch (RuntimeException e) { return false; }
    }

    private static final class Pending {
        final long revision;
        final Callback callback;
        final AtomicBoolean delivered = new AtomicBoolean();
        volatile boolean sent;
        Pending(long revision, Callback callback) { this.revision = revision; this.callback = callback; }
        void finish(Result result) {
            if (delivered.compareAndSet(false, true)) new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onResult(result);
            });
        }
    }

    private void complete(Pending work, Result result) {
        main.post(() -> {
            if (pending != work || generation != work.revision) return;
            pending = null; work.finish(result);
            if (result.outcomeUnknown) disconnect();
        });
    }
    private static Result failure(String code) { return new Result(false, false, code); }
    private void reply(Callback callback, Result result) {
        if (callback != null) main.post(() -> callback.onResult(result));
    }
}
