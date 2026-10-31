package cn.local.tickethelper;

import cn.local.tickethelper.catalog.CatalogProject;
import cn.local.tickethelper.catalog.CatalogSession;
import cn.local.tickethelper.catalog.CatalogTier;
import java.util.List;

/** Ephemeral, bounded public catalog. Coordinates, screenshots and account data are never retained here. */
public final class CatalogStore {
    public static final class Snapshot {
        public final String stage, status, query;
        public final CatalogProject event;
        public final CatalogSession session;
        public final List<CatalogProject> projects;
        public final List<CatalogSession> sessions;
        public final List<CatalogTier> tiers;
        public final long readAtMs, revision;
        public final java.time.Instant openingAt;
        private Snapshot(String stage, String status, String query, CatalogProject event, CatalogSession session,
                         List<CatalogProject> projects, List<CatalogSession> sessions, List<CatalogTier> tiers,
                         long readAtMs, long revision, java.time.Instant openingAt) {
            this.stage = stage; this.status = status; this.query = query; this.event = event; this.session = session;
            this.projects = List.copyOf(projects); this.sessions = List.copyOf(sessions); this.tiers = List.copyOf(tiers);
            this.readAtMs = readAtMs; this.revision = revision;
            this.openingAt=openingAt;
        }
    }
    private static Snapshot current = new Snapshot("IDLE", "搜索后从大麦当前可见结果中选择演出。", "", null, null,
            List.of(), List.of(), List.of(), 0, 0, null);
    private CatalogStore() { }
    public static synchronized Snapshot get() { return current; }
    static synchronized void update(String stage, String status, String query, CatalogProject event,
                                    CatalogSession session, List<CatalogProject> projects,
                                    List<CatalogSession> sessions, List<CatalogTier> tiers, long readAtMs) {
        update(stage,status,query,event,session,projects,sessions,tiers,readAtMs,null);
    }
    static synchronized void update(String stage,String status,String query,CatalogProject event,
                                   CatalogSession session,List<CatalogProject> projects,List<CatalogSession> sessions,
                                   List<CatalogTier> tiers,long readAtMs,java.time.Instant openingAt) {
        current = new Snapshot(stage, status, query == null ? "" : query, event, session,
                projects, sessions, tiers, readAtMs, current.revision + 1,openingAt);
    }
}
