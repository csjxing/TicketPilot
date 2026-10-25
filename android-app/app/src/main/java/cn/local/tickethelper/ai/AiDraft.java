package cn.local.tickethelper.ai;

import cn.local.tickethelper.core.MonitorConfig;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** A model suggestion can only become an unarmed draft requiring live catalog re-verification. */
public final class AiDraft {
    public final MonitorConfig config;
    public final String summary;
    private AiDraft(MonitorConfig config) {
        this.config = config;
        summary = "演出：" + config.eventKeyword + "\n城市：" + label(config.city)
                + "\n日期：" + label(config.date) + "  " + label(config.time)
                + "\n接受票价：" + (config.prices.isEmpty() ? "待选择" : config.prices.toString() + " 元")
                + "\n张数：1 张\n总预算：" + (config.maxTotal == 0 ? "待核对" : config.maxTotal + " 元")
                + "\n停止时间：按所选场次开始时间，待大麦核对\n保存后保持暂停，必须从真实大麦重新选择核对。";
    }
    private static String label(String s) { return s.isEmpty() ? "待选择" : s; }
    public static AiDraft fromResponse(String response, MonitorConfig current) {
        try {
            if (response == null || response.length() > 20000) return null;
            String raw = response.trim();
            if (!raw.startsWith("{")) {
                Matcher blocks = Pattern.compile("```json\\s*(\\{[\\s\\S]*?\\})\\s*```", Pattern.CASE_INSENSITIVE).matcher(raw);
                if (!blocks.find()) return null;
                raw = blocks.group(1);
                if (blocks.find()) return null;
            }
            // Android's JSON implementation accepts duplicate members; reject them independently.
            Set<String> seen = plainUniqueKeys(raw);
            if (seen == null) return null;
            JSONTokener reader = new JSONTokener(raw);
            Object parsed = reader.nextValue();
            if (!(parsed instanceof JSONObject) || reader.nextClean() != 0) return null;
            JSONObject root = (JSONObject) parsed;
            if (root.length() != 1 || !root.has("ticketpilot_draft")) return null;
            JSONObject d = root.getJSONObject("ticketpilot_draft");
            Set<String> allowed = Set.of("event", "city", "date", "time", "prices", "quantity", "maxTotal");
            for (java.util.Iterator<String> keysIterator = d.keys(); keysIterator.hasNext();)
                if (!allowed.contains(keysIterator.next())) return null;
            if (seen.size() != d.length() + 1) return null;
            String event = text(d, "event", 120), city = text(d, "city", 40);
            String date = text(d, "date", 10), time = text(d, "time", 5);
            if (event.length() < 2) return null;
            if (!date.isEmpty() && (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(date).toString().equals(date))) return null;
            if (!time.isEmpty() && (!time.matches("[0-9]{2}:[0-9]{2}") || !LocalTime.parse(time).toString().equals(time))) return null;
            if (d.has("quantity") && integer(d.get("quantity")) != 1) return null;
            List<Integer> prices = new ArrayList<>();
            if (d.has("prices")) {
                JSONArray a = d.getJSONArray("prices");
                if (a.length() > 20) return null;
                for (int i = 0; i < a.length(); i++) {
                    int price = integer(a.get(i));
                    if (price < 1 || price > 100000 || prices.contains(price)) return null;
                    prices.add(price);
                }
            }
            int budget = d.has("maxTotal") ? integer(d.get("maxTotal")) : 0;
            if (budget < 0 || budget > 100000) return null;
            if (budget > 0 && prices.stream().anyMatch(p -> p > budget)) return null;
            Instant deadline = date.isEmpty() || time.isEmpty() ? null
                    : LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            long min = current != null && current.minIntervalMs >= MonitorConfig.MIN_INTERVAL_MS && current.minIntervalMs <= MonitorConfig.MAX_INTERVAL_MS ? current.minIntervalMs : 5000;
            long max = current != null && current.maxIntervalMs >= min && current.maxIntervalMs <= MonitorConfig.MAX_INTERVAL_MS ? current.maxIntervalMs : Math.max(min, 10000);
            MonitorConfig.Mode mode = current != null && current.mode == MonitorConfig.Mode.PREPARE
                    ? MonitorConfig.Mode.PREPARE : MonitorConfig.Mode.ALERT;
            return new AiDraft(new MonitorConfig(event, city, date, time, prices, 1, budget, deadline, min, max, mode));
        } catch (Exception invalid) { return null; }
    }
    private static int integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        long number = ((Number) value).longValue();
        if (number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) throw new IllegalArgumentException();
        return (int) number;
    }
    private static Set<String> plainUniqueKeys(String raw) {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) != '"') continue;
            int begin = ++i;
            while (i < raw.length()) {
                if (raw.charAt(i) == '\\') { i += 2; continue; }
                if (raw.charAt(i) == '"') break;
                i++;
            }
            if (i >= raw.length()) return null;
            String literal = raw.substring(begin, i);
            int next = i + 1;
            while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) next++;
            if (next < raw.length() && raw.charAt(next) == ':') {
                if (!literal.matches("[A-Za-z][A-Za-z0-9_]*") || !keys.add(literal)) return null;
            }
        }
        return keys;
    }
    private static String text(JSONObject object, String key, int bound) throws Exception {
        if (!object.has(key)) return "";
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        String text = ((String) value).trim();
        if (text.length() > bound || text.chars().anyMatch(Character::isISOControl)
                || !AiRedactor.clean(text, List.of(), bound).equals(text)) throw new IllegalArgumentException();
        return text;
    }
}
