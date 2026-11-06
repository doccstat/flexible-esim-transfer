package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Bundle implements Parcelable {
    private final Map<String, Object> data = new HashMap<>();
    public void putInt(String key, int value) { data.put(key, value); }
    public void putString(String key, String value) { data.put(key, value); }
    public void putBoolean(String key, boolean value) { data.put(key, value); }
    public int getInt(String key, int fallback) { return (Integer) data.getOrDefault(key, fallback); }
    public String getString(String key) { return (String) data.get(key); }
    public boolean getBoolean(String key) { return (Boolean) data.getOrDefault(key, false); }
    public boolean containsKey(String key) { return data.containsKey(key); }
    public void remove(String key) { data.remove(key); }
    public java.util.Set<String> keySet() { return data.keySet(); }
    public <T extends Parcelable> void putParcelableArrayList(String key, ArrayList<T> value) { data.put(key, value); }
    @SuppressWarnings("unchecked") public <T> ArrayList<T> getParcelableArrayList(String key, Class<? extends T> type) {
        return (ArrayList<T>) data.get(key);
    }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel parcel, int flags) { throw new UnsupportedOperationException(); }
}
