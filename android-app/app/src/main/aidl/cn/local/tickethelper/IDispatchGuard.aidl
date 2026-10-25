package cn.local.tickethelper;

/** Single-use, bounded callback to revalidate the captured page immediately before input. */
interface IDispatchGuard {
    boolean allow();
}
