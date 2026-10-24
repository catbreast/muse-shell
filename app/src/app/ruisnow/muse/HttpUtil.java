package app.ruisnow.muse;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Relay HTTP on OkHttp (0.6.11; was HttpURLConnection up to 0.6.10).
 * Why the switch (agy review, 2026-10-23): the poll worker kept wedging
 * inside the platform stack with no exception — suspects were netd DNS
 * lookups with no app-side timeout and the shared keep-alive pool handing
 * out half-open connections. OkHttp gives (a) a per-call cancel() that
 * reliably aborts a blocked execute(), (b) a pluggable Dns so lookups get
 * a hard wait cap, (c) a pool that probes and evicts stale connections.
 */
public class HttpUtil {
    public static class Resp {
        public int code;
        public String body;
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // DNS lookups run on daemon threads and the caller waits at most 8 s.
    // A lookup stuck in native code may linger on its thread, but it can no
    // longer hold the poll worker hostage.
    private static final ExecutorService DNS_EXEC = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "muse-dns");
            t.setDaemon(true);
            return t;
        }
    });
    private static final Dns DNS_WITH_TIMEOUT = new Dns() {
        @Override public List<InetAddress> lookup(final String hostname) throws UnknownHostException {
            Future<List<InetAddress>> f = DNS_EXEC.submit(new Callable<List<InetAddress>>() {
                @Override public List<InetAddress> call() throws Exception {
                    return Arrays.asList(InetAddress.getAllByName(hostname));
                }
            });
            try {
                return f.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                f.cancel(true);
                throw new UnknownHostException(hostname);
            }
        }
    };

    private static final OkHttpClient BASE = new OkHttpClient.Builder()
            .dns(DNS_WITH_TIMEOUT)
            .retryOnConnectionFailure(true)
            .build();

    // The call currently on the wire. The supervisor cancels it to force a
    // wedged execute() to throw at once, so the worker recovers in-process
    // instead of the process being killed (0.6.10 stage-1 heal, now with a
    // cancel that actually interrupts the blocked read).
    static volatile Call inFlight;

    public static void cancelInFlight() {
        Call c = inFlight;
        if (c != null) c.cancel();
    }

    public static Resp request(String method, String urlStr, String token, String deviceId,
                               String body, int connectMs, int readMs) throws Exception {
        Request.Builder rb = new Request.Builder()
                .url(urlStr)
                .header("X-Token", token == null ? "" : token)
                .header("X-Device", deviceId == null ? "" : deviceId);
        if ("POST".equals(method)) {
            rb.post(RequestBody.create(JSON, body == null ? "" : body));
        } else if ("PUT".equals(method)) {
            rb.put(RequestBody.create(JSON, body == null ? "" : body));
        } else {
            rb.get();
        }
        // Per-request timeouts via a derived client (shares pool/dispatcher).
        OkHttpClient client = BASE.newBuilder()
                .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(readMs, 15000), TimeUnit.MILLISECONDS)
                .build();
        Call call = client.newCall(rb.build());
        inFlight = call;
        try {
            Response resp = call.execute();
            try {
                Resp r = new Resp();
                r.code = resp.code();
                ResponseBody b = resp.body();
                r.body = b == null ? "" : b.string();
                return r;
            } finally {
                resp.close();
            }
        } finally {
            if (inFlight == call) inFlight = null;
        }
    }
}
