package android.telephony.euicc;

import com.lixingchi.flexibleesimtransfer.HookHarness;
import java.util.concurrent.Executor;

public class EuiccCardManager {
    public interface ResultCallback<T> { void onComplete(int result, T value); }
    public int calls;
    public int result;
    public String card;
    public byte[] payload;
    private void invoke(String name, String eid, Object[] args, Class<?>[] types, byte[] bytes) {
        HookHarness.call(EuiccCardManager.class, name, this, types, args, (r, a) -> {
            calls++; card = eid; payload = bytes;
            @SuppressWarnings("unchecked") ResultCallback<Object> callback = (ResultCallback<Object>) a[a.length - 1];
            ((Executor) a[a.length - 2]).execute(() -> callback.onComplete(result, bytes));
            return null;
        });
    }
    public void requestEuiccChallenge(String eid, Executor executor, ResultCallback<byte[]> callback) {
        invoke("requestEuiccChallenge", eid, new Object[]{eid, executor, callback},
                new Class<?>[]{String.class, Executor.class, ResultCallback.class}, null);
    }
    public void authenticateServer(String eid, String matchingId, byte[] signed, byte[] signature,
                                   byte[] keyId, byte[] certificate, Executor executor, ResultCallback<byte[]> callback) {
        invoke("authenticateServer", eid, new Object[]{eid, matchingId, signed, signature, keyId, certificate, executor, callback},
                new Class<?>[]{String.class, String.class, byte[].class, byte[].class, byte[].class, byte[].class,
                        Executor.class, ResultCallback.class}, signed);
    }
    public void prepareDownload(String eid, byte[] hash, byte[] signed, byte[] signature, byte[] certificate,
                                Executor executor, ResultCallback<byte[]> callback) {
        invoke("prepareDownload", eid, new Object[]{eid, hash, signed, signature, certificate, executor, callback},
                new Class<?>[]{String.class, byte[].class, byte[].class, byte[].class, byte[].class,
                        Executor.class, ResultCallback.class}, signed);
    }
    public void loadBoundProfilePackage(String eid, byte[] bound, Executor executor, ResultCallback<byte[]> callback) {
        invoke("loadBoundProfilePackage", eid, new Object[]{eid, bound, executor, callback},
                new Class<?>[]{String.class, byte[].class, Executor.class, ResultCallback.class}, bound);
    }
}
