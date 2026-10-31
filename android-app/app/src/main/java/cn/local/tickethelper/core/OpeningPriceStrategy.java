package cn.local.tickethelper.core;
import java.util.List;

/** One monitor run: primary opening tier first; only verified shortage unlocks fallback tiers. */
public final class OpeningPriceStrategy {
    private String identity="";
    private boolean primaryOnly;
    public void begin(MonitorConfig c,long now) {
        identity=identity(c);
        primaryOnly=c.openingPrice>0 && c.saleAt!=null && now<=c.saleAt.toEpochMilli()+SaleTiming.OPENING_BURST_MS;
    }
    public List<Integer> prices(MonitorConfig c) {
        if(primaryOnly && identity.equals(identity(c)))return List.of(c.openingPrice);
        if(c.openingPrice<=0 || !c.prices.contains(c.openingPrice))return c.prices;
        java.util.ArrayList<Integer> ordered=new java.util.ArrayList<>(c.prices);ordered.remove(Integer.valueOf(c.openingPrice));ordered.add(0,c.openingPrice);
        return List.copyOf(ordered);
    }
    public boolean shortage(MonitorConfig c,Decision d) {
        if(!primaryOnly || !identity.equals(identity(c)) || d==null || d.state!=Decision.State.OUT_OF_STOCK
                || !("selected_session_explicitly_unavailable".equals(d.reason)
                || "all_target_tiers_explicitly_unavailable".equals(d.reason)))return false;
        primaryOnly=false;return true;
    }
    private String identity(MonitorConfig c) {
        return c.eventKeyword+"|"+c.city+"|"+c.date+"|"+c.time+"|"+c.prices+"|"+c.openingPrice+"|"+c.saleAt;
    }
}
