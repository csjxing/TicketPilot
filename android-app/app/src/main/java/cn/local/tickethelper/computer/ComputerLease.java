package cn.local.tickethelper.computer;

/** Phone-local single-operator lease. ADB pairing remains the system security boundary. */
public final class ComputerLease {
    public static final long DURATION_MS = 15 * 60_000L;
    public final String session, owner;
    public final long startedWall, startedElapsed;
    public ComputerLease(String session, String owner, long wall, long elapsed) {
        if (!id(session) || !(owner.isEmpty() || id(owner)) || wall <= 0 || elapsed < 0)
            throw new IllegalArgumentException("Invalid computer session");
        this.session=session; this.owner=owner; startedWall=wall; startedElapsed=elapsed;
    }
    public static boolean id(String value) { return value != null && value.matches("[a-f0-9]{32}"); }
    public long remaining(long wall, long elapsed) {
        long w=wall-startedWall,e=elapsed-startedElapsed;
        if(w<0||e<0||w>=DURATION_MS||e>=DURATION_MS||Math.abs(w-e)>10_000)return 0;
        return DURATION_MS-Math.max(w,e);
    }
    public ComputerLease claim(String who,long wall,long elapsed) {
        if(!id(who)||remaining(wall,elapsed)==0||(!owner.isEmpty()&&!owner.equals(who)))
            throw new IllegalArgumentException("Session unavailable or owned by another computer");
        return new ComputerLease(session,who,startedWall,startedElapsed);
    }
    public boolean permits(String who,long wall,long elapsed) {
        return id(who)&&owner.equals(who)&&remaining(wall,elapsed)>0;
    }
}
