package android.os;

import java.util.ArrayList;
import java.util.List;

/** Deterministic timeout clock; not a model of Android's looper scheduling. */
public class Handler {
    public final List<Runnable> delayed = new ArrayList<>();
    public boolean postDelayed(Runnable runnable, long delay) { delayed.add(runnable); return true; }
    public void removeCallbacks(Runnable runnable) { delayed.remove(runnable); }
    public void elapse() { for (Runnable runnable : List.copyOf(delayed)) runnable.run(); }
}
