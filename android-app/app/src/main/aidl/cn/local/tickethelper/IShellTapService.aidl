package cn.local.tickethelper;

import cn.local.tickethelper.IDispatchGuard;

/** Private binder contract: no arbitrary commands, files, gestures or key events. */
interface IShellTapService {
    void destroy() = 16777114;
    boolean initialize(long session, long generation) = 1;
    boolean ping(long session) = 2;
    String tap(long session, long generation, int x, int y, long deadlineElapsedRealtime, IDispatchGuard guard) = 3;
    void cancel(long session, long nextGeneration) = 4;
}
