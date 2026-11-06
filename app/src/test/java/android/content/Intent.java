package android.content;
import java.util.HashMap;
import java.util.Map;
public class Intent {
    private final Map<String, Object> extras = new HashMap<>();
    public Intent putExtra(String key, boolean value) { extras.put(key, value); return this; }
    public Intent putExtra(String key, int value) { extras.put(key, value); return this; }
    public int getIntExtra(String key, int fallback) { return (Integer) extras.getOrDefault(key, fallback); }
    public <T> T getParcelableExtra(String key, Class<T> type) { return type.cast(extras.get(key)); }
}
