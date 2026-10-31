package cn.local.tickethelper.plans;

import cn.local.tickethelper.core.MonitorConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Public local session settings only. Permissions, attendees and order grants never enter this library. */
public final class SavedPlans {
    public static final int LIMIT=50;
    private SavedPlans() { }
    public static String key(MonitorConfig c) {
        return new JSONArray().put(c.eventKeyword).put(c.city).put(c.date).put(c.time).toString();
    }
    public static MonitorConfig draft(MonitorConfig c) {
        return new MonitorConfig(c.eventKeyword,c.city,c.date,c.time,c.prices,c.quantity,c.maxTotal,
                c.deadline,c.minIntervalMs,c.maxIntervalMs,c.mode==MonitorConfig.Mode.LOCK_ORDER?MonitorConfig.Mode.PREPARE:c.mode,
                c.saleAt,c.followUpMinutes);
    }
    public static List<MonitorConfig> read(String raw) {
        try {
            if(raw==null || raw.length()>512000)throw new IllegalArgumentException();
            JSONArray array=new JSONArray(raw);
            if(array.length()>LIMIT)throw new IllegalArgumentException();
            List<MonitorConfig> result=new ArrayList<>();
            for(int i=0;i<array.length();i++) {
                JSONObject o=array.getJSONObject(i);JSONArray prices=o.getJSONArray("prices");List<Integer> values=new ArrayList<>();
                if(prices.length()>20)throw new IllegalArgumentException();
                for(int j=0;j<prices.length();j++)values.add(prices.getInt(j));
                MonitorConfig c=new MonitorConfig(o.getString("event"),o.getString("city"),o.getString("date"),o.getString("time"),
                        values,o.getInt("quantity"),o.getInt("maxTotal"),Instant.parse(o.getString("deadline")),
                        o.getLong("min"),o.getLong("max"),MonitorConfig.Mode.valueOf(o.getString("mode")),
                        o.isNull("saleAt")?null:Instant.parse(o.getString("saleAt")),o.getInt("follow"));
                if(!valid(c) || result.stream().anyMatch(old->key(old).equals(key(c))))throw new IllegalArgumentException();
                result.add(draft(c));
            }
            return List.copyOf(result);
        } catch(Exception invalid){throw new IllegalArgumentException("已保存场次资料无法读取，请保留数据并检查诊断。",invalid);}
    }
    public static List<MonitorConfig> put(List<MonitorConfig> existing,MonitorConfig c) {
        List<MonitorConfig> result=new ArrayList<>(existing);
        if(!valid(c))return List.copyOf(result);
        result.removeIf(old->key(old).equals(key(c)));
        if(result.size()>=LIMIT)throw new IllegalArgumentException("最多保存50个场次，请先删除不需要的场次。");
        result.add(0,draft(c));return List.copyOf(result);
    }
    public static String write(List<MonitorConfig> plans) {
        if(plans.size()>LIMIT)throw new IllegalArgumentException("场次过多");
        try {
            JSONArray result=new JSONArray();
            for(MonitorConfig c:plans) {
                if(!valid(c))throw new IllegalArgumentException("场次不完整");
                c=draft(c);
                result.put(new JSONObject().put("event",c.eventKeyword).put("city",c.city).put("date",c.date).put("time",c.time)
                        .put("prices",new JSONArray(c.prices)).put("quantity",c.quantity).put("maxTotal",c.maxTotal)
                        .put("deadline",c.deadline.toString()).put("min",c.minIntervalMs).put("max",c.maxIntervalMs)
                        .put("mode",c.mode.name()).put("saleAt",c.saleAt==null?JSONObject.NULL:c.saleAt.toString()).put("follow",c.followUpMinutes));
            }
            return result.toString();
        } catch(Exception invalid){throw new IllegalArgumentException("场次保存失败",invalid);}
    }
    private static boolean valid(MonitorConfig c) {
        return c!=null && c.eventKeyword.length()<=180 && c.city.length()<=40 && c.validate().isEmpty();
    }
}
