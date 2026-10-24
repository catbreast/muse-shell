package app.ruisnow.muse;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 浏览器宿主（0.6.0，轻量版）—— 给 Muse 一套能真正操作网页的工具。
 *
 * 借鉴 DSH（deepseek-harness）的四件套，代码为本工程自有实现：
 *  ① 结构化 DOM 快照：只收可交互元素，带 role/name/bounds/inView/disabled；
 *     不用截图 OCR 做定位。
 *  ② 稳定 ref：ref = 元素指纹（tag|type|role|名|href host+path|DOM 路径）的
 *     FNV 短哈希，写进 data-muse-ref，跨快照保持一致；同指纹加 -2/-3 并标 dup。
 *  ③ 真差分：与上一轮快照按 ref 比对，回 added/removed/changed/unchanged。
 *  ④ 动作后置校验：click/type 后回页面指纹变化 + 最小差分，当场知道点没点动。
 *
 * 单页签；页面放在 BrowserActivity 里前台可见（用户随时能看、能接管）。
 * WebView 用 MutableContextWrapper 持有：Activity 在时挂 Activity，
 * Activity 销毁后退回应用上下文，页面与登录态不丢。
 * 只允许 http/https（about:blank 为内部初始页）。
 */
public class BrowserHost {
    private static BrowserHost instance;

    public static synchronized BrowserHost get(Context ctx) {
        if (instance == null) instance = new BrowserHost(ctx.getApplicationContext());
        return instance;
    }

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MutableContextWrapper wrapper;
    private WebView wv;
    private volatile int generation = 0;
    private volatile String lastUrl = "";
    private final Map<String, String> prevNodes = new LinkedHashMap<String, String>();
    private CountDownLatch loadLatch;

    private BrowserHost(Context appCtx) {
        this.appCtx = appCtx;
    }

    // ---------------- 生命周期 ----------------

    /** 在主线程跑一段代码并等待结果（分发线程调用）。 */
    private <T> T onMain(Callable<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final AtomicReference<T> out = new AtomicReference<T>();
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    out.set(c.call());
                } catch (Exception e) {
                    err.set(e);
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new Exception("browser_main_timeout");
        }
        if (err.get() != null) throw err.get();
        return out.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView ensureWebView() {
        // 必须在主线程调用
        if (wv != null) return wv;
        wrapper = new MutableContextWrapper(appCtx);
        WebView w = new WebView(wrapper);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                generation++;
                prevNodes.clear();
            }

            @Override public void onPageFinished(WebView v, String url) {
                lastUrl = url == null ? "" : url;
                CountDownLatch l = loadLatch;
                if (l != null) l.countDown();
            }
        });
        w.setWebChromeClient(new WebChromeClient());
        // 离屏时也给一个真实尺寸，保证 DOM 布局与坐标有意义
        DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        w.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        w.layout(0, 0, dm.widthPixels, dm.heightPixels);
        wv = w;
        return wv;
    }

    /** BrowserActivity 创建时挂载 WebView。 */
    public View attach(final android.app.Activity act) throws Exception {
        return onMain(new Callable<View>() {
            @Override public View call() {
                WebView w = ensureWebView();
                wrapper.setBaseContext(act);
                ViewGroup p = (ViewGroup) w.getParent();
                if (p != null) p.removeView(w);
                return w;
            }
        }, 8000);
    }

    /** BrowserActivity 销毁时退回应用上下文（页面不销毁）。 */
    public void detach() {
        main.post(new Runnable() {
            @Override public void run() {
                if (wv != null && wrapper != null) {
                    ViewGroup p = (ViewGroup) wv.getParent();
                    if (p != null) p.removeView(wv);
                    wrapper.setBaseContext(appCtx);
                }
            }
        });
    }

    private void showActivity() {
        Intent i = new Intent(appCtx, BrowserActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appCtx.startActivity(i);
    }

    // ---------------- JS 基础设施 ----------------

    private String eval(final String js, long timeoutMs) throws Exception {
        // 关键：evaluateJavascript 必须在主线程发起，但等待必须在分发线程——
        // 回调要靠主线程派发，若在主线程里 latch.await 等回调就是死锁（0.6.0 的坑）。
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<String>();
        final AtomicReference<Exception> err = new AtomicReference<Exception>();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    WebView w = ensureWebView();
                    w.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            out.set(v);
                            latch.countDown();
                        }
                    });
                } catch (Exception e) {
                    err.set(e);
                    latch.countDown();
                }
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new Exception("browser_js_timeout");
        }
        if (err.get() != null) throw err.get();
        String v = out.get();
        if (v == null || "null".equals(v)) return "";
        // evaluateJavascript 的返回值是 JSON 编码的字符串，需解一层
        Object parsed = new JSONTokener(v).nextValue();
        return parsed == null ? "" : String.valueOf(parsed);
    }

    /** 与 DSH 同思路的页面侧公共函数：命名/角色/指纹/ref 分配/可见性。 */
    private static final String JS_LIB =
            "function __nm(el){"
            + "var a=el.getAttribute('aria-label');if(a)return a.trim().slice(0,120);"
            + "var tag=el.tagName.toLowerCase();"
            + "if(tag==='input'||tag==='textarea'||tag==='select'){"
            + "if(el.id){var lab=document.querySelector('label[for=\"'+el.id+'\"]');"
            + "if(lab&&lab.innerText)return lab.innerText.trim().slice(0,120);}"
            + "if(el.placeholder)return el.placeholder.slice(0,120);"
            + "if((el.type==='submit'||el.type==='button')&&el.value)return String(el.value).slice(0,120);"
            + "if(el.name)return el.name.slice(0,120);return '';}"
            + "var t=(el.innerText||'').trim().replace(/\\s+/g,' ');"
            + "if(t)return t.slice(0,120);"
            + "if(el.getAttribute('alt'))return el.getAttribute('alt').slice(0,120);"
            + "if(el.getAttribute('title'))return el.getAttribute('title').slice(0,120);"
            + "return '';}"
            + "function __rl(el){"
            + "var r=el.getAttribute('role');if(r)return r;"
            + "var tag=el.tagName.toLowerCase();"
            + "if(tag==='a')return 'link';if(tag==='button')return 'button';"
            + "if(tag==='input')return 'input:'+(el.type||'text');"
            + "if(tag==='textarea')return 'input:textarea';"
            + "if(tag==='select')return 'select';"
            + "return tag;}"
            + "function __vis(el){"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<=0||r.height<=0)return false;"
            + "var st=getComputedStyle(el);"
            + "return st.visibility!=='hidden'&&st.display!=='none';}"
            + "function __dp(el){"
            + "var parts=[],n=el,d=0;"
            + "while(n&&n!==document.body&&d<14){"
            + "var tag=n.tagName?n.tagName.toLowerCase():'x';"
            + "var idx=0,p=n.parentElement;"
            + "if(p){var sib=p.children;for(var i=0;i<sib.length;i++){if(sib[i]===n)break;"
            + "if(sib[i].tagName===n.tagName)idx++;}}"
            + "parts.unshift(tag+':'+idx);n=p;d++;}"
            + "return parts.join('/');}"
            + "function __fp(el){"
            + "var tag=el.tagName.toLowerCase();"
            + "var ty=el.getAttribute('type')||'';"
            + "var href='';"
            + "if(tag==='a'&&el.href){try{var u=new URL(el.href);href=u.host+u.pathname;}catch(e){href=String(el.href).slice(0,80);}}"
            + "return tag+'|'+ty+'|'+__rl(el)+'|'+__nm(el)+'|'+href+'|'+__dp(el);}"
            + "function __hash(s){"
            + "var h=0x811c9dc5;"
            + "for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h=(h*0x01000193)>>>0;}"
            + "return ('0000000'+h.toString(16)).slice(-7);}"
            + "function __ref(el,used){"
            + "var base='r'+__hash(__fp(el));var ref=base,dup=1;"
            + "while(used[ref]){dup++;ref=base+'-'+dup;}"
            + "used[ref]=1;"
            + "if(el.getAttribute('data-muse-ref')!==ref)el.setAttribute('data-muse-ref',ref);"
            + "return ref;}"
            + "var __SEL='a[href],button,input,select,textarea,[role],[onclick],[tabindex],[contenteditable]';"
            + "if(!window.__museMut){window.__museMut=0;"
            + "try{new MutationObserver(function(){window.__museMut++;})"
            + ".observe(document.documentElement,{childList:true,subtree:true,"
            + "attributes:true,attributeFilter:['value','checked','selected','disabled','href','src','class']});}catch(e){}}";

    private static final String SNAPSHOT_JS =
            "(function(){" + JS_LIB
            + "var els=document.querySelectorAll(__SEL);"
            + "var used={},out=[];"
            + "for(var i=0;i<els.length&&out.length<400;i++){"
            + "var el=els[i];"
            + "if(!__vis(el))continue;"
            + "var r=el.getBoundingClientRect();"
            + "var ref=__ref(el,used);"
            + "out.push({ref:ref,role:__rl(el),name:__nm(el),"
            + "x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),"
            + "w:Math.round(r.width),h:Math.round(r.height),"
            + "inView:(r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth),"
            + "disabled:!!el.disabled});}"
            + "return JSON.stringify({url:location.href,title:document.title||'',nodes:out});})()";

    private static final String TEXT_JS =
            "(function(){"
            + "var t=(document.body&&document.body.innerText)||'';"
            + "var lines=t.split('\\n').map(function(s){return s.trim();})"
            + ".filter(function(s){return s.length>0;});"
            + "return JSON.stringify({lines:lines.slice(0,40)});})()";

    private static final String FP_JS =
            "(function(){" + JS_LIB
            + "var els=document.querySelectorAll('[data-muse-ref]');"
            + "var s='';"
            + "for(var i=0;i<els.length;i++){var r=els[i].getBoundingClientRect();"
            + "if(r.bottom>0&&r.top<window.innerHeight){s+=els[i].getAttribute('data-muse-ref')+',';}}"
            + "var tl=(document.body&&document.body.innerText||'').length;"
            + "return JSON.stringify({fp:__hash(s+'|'+(window.__museMut||0)+'|'+tl)});})()";

    // ---------------- 对外工具 ----------------

    public JSONObject open(String url) throws Exception {
        String u = (url == null ? "" : url.trim());
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new Exception("browser_bad_url: 只允许 http/https");
        }
        showActivity();
        // 等 Activity 把 WebView 建起来
        long end = System.currentTimeMillis() + 8000;
        while (wv == null && System.currentTimeMillis() < end) Thread.sleep(100);
        final String target = u;
        Boolean loaded = onMain(new Callable<Boolean>() {
            @Override public Boolean call() {
                WebView w = ensureWebView();
                loadLatch = new CountDownLatch(1);
                if (target.equals(w.getUrl())) {
                    loadLatch.countDown();
                    return true;
                }
                w.loadUrl(target);
                return true;
            }
        }, 8000);
        CountDownLatch l = loadLatch;
        if (l != null) l.await(25, TimeUnit.SECONDS);
        Thread.sleep(500); // 等首屏渲染稳定
        JSONObject o = new JSONObject();
        o.put("url", lastUrl.isEmpty() ? u : lastUrl);
        o.put("title", evalTitle());
        o.put("generation", generation);
        return o;
    }

    private String evalTitle() {
        try {
            String t = eval("(function(){return document.title||'';})()", 6000);
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 工具面快照：中继通道在约 7KB 以上不可靠（实测 seq 输出 8KB 即断），
     * 因此节点按字节预算分页返回；差分仍按全量节点计算（手机侧内部持有）。
     * offset 翻页可取后续节点，ref 跨页稳定。
     */
    public JSONObject snapshot(boolean includeText, int offset) throws Exception {
        JSONObject full = snapshotFull(includeText);
        JSONArray all = full.getJSONArray("nodes");
        int start = Math.max(0, Math.min(offset, all.length()));
        JSONArray page = new JSONArray();
        int bytes = 0;
        int i = start;
        for (; i < all.length(); i++) {
            String s = all.getJSONObject(i).toString();
            int b = s.getBytes("UTF-8").length + 1;
            if (page.length() >= 10 && bytes + b > 4000) break;
            page.put(all.getJSONObject(i));
            bytes += b;
        }
        full.put("nodes", page);
        full.put("offset", start);
        full.put("returned", page.length());
        if (i < all.length()) {
            full.put("more", true);
            full.put("nextOffset", i);
        }
        return full;
    }

    private JSONObject snapshotFull(boolean includeText) throws Exception {
        if (wv == null) throw new Exception("browser_not_open: 先用 browser.open 打开页面");
        String raw = eval(SNAPSHOT_JS, 12000);
        JSONObject page = new JSONObject(raw);
        JSONArray nodes = page.optJSONArray("nodes");
        if (nodes == null) nodes = new JSONArray();
        Map<String, String> now = new LinkedHashMap<String, String>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            now.put(n.getString("ref"), n.optString("role") + "|" + n.optString("name")
                    + "|" + n.optBoolean("inView") + "|" + n.optBoolean("disabled"));
        }
        List<String> added = new ArrayList<String>();
        List<String> removed = new ArrayList<String>();
        List<String> changed = new ArrayList<String>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            String before = prevNodes.get(e.getKey());
            if (before == null) added.add(e.getKey());
            else if (!before.equals(e.getValue())) changed.add(e.getKey());
        }
        for (String ref : prevNodes.keySet()) {
            if (!now.containsKey(ref)) removed.add(ref);
        }
        boolean first = prevNodes.isEmpty();
        prevNodes.clear();
        prevNodes.putAll(now);

        JSONObject o = new JSONObject();
        o.put("url", page.optString("url"));
        o.put("title", page.optString("title"));
        o.put("generation", generation);
        o.put("count", nodes.length());
        o.put("nodes", nodes);
        if (!first) {
            JSONObject d = new JSONObject();
            d.put("added", capRefs(added));
            d.put("removed", capRefs(removed));
            d.put("changed", capRefs(changed));
            d.put("addedCount", added.size());
            d.put("removedCount", removed.size());
            d.put("changedCount", changed.size());
            d.put("unchangedCount", now.size() - added.size() - changed.size());
            if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) d.put("unchanged", true);
            o.put("diff", d);
        }
        if (includeText) {
            JSONObject t = new JSONObject(eval(TEXT_JS, 8000));
            JSONArray lines = t.optJSONArray("lines");
            JSONArray capped = new JSONArray();
            int tb = 0;
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    String ln = lines.getString(i);
                    int b = ln.getBytes("UTF-8").length + 1;
                    if (capped.length() > 0 && tb + b > 2000) break;
                    capped.put(ln);
                    tb += b;
                }
            }
            o.put("textLines", capped);
        }
        return o;
    }

    private static JSONArray capRefs(List<String> refs) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < refs.size() && i < 80; i++) a.put(refs.get(i));
        return a;
    }

    public JSONObject find(String query, String role) throws Exception {
        if (wv == null) throw new Exception("browser_not_open: 先用 browser.open 打开页面");
        String js = "(function(){" + JS_LIB
                + "var q=" + JSONObject.quote(query == null ? "" : query) + ";"
                + "var wantRole=" + JSONObject.quote(role == null ? "" : role) + ";"
                + "var els=document.querySelectorAll(__SEL);"
                + "var used={},out=[];"
                + "var existing=document.querySelectorAll('[data-muse-ref]');"
                + "for(var j=0;j<existing.length;j++)used[existing[j].getAttribute('data-muse-ref')]=1;"
                + "for(var i=0;i<els.length&&out.length<10;i++){"
                + "var el=els[i];"
                + "if(!__vis(el))continue;"
                + "var nm=__nm(el),rl=__rl(el);"
                + "if(wantRole&&rl.indexOf(wantRole)===-1)continue;"
                + "if(q&&nm.indexOf(q)===-1&&rl.indexOf(q)===-1)continue;"
                + "var r=el.getBoundingClientRect();"
                + "var ref=__ref(el,used);"
                + "out.push({ref:ref,role:rl,name:nm,"
                + "x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),"
                + "inView:(r.bottom>0&&r.top<window.innerHeight)});}"
                + "return JSON.stringify({found:out.length,candidates:out});})()";
        return new JSONObject(eval(js, 12000));
    }

    public JSONObject click(String ref) throws Exception {
        if (wv == null) throw new Exception("browser_not_open: 先用 browser.open 打开页面");
        String ptJs = "(function(){"
                + "var el=document.querySelector('[data-muse-ref=\"" + ref + "\"]');"
                + "if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});"
                + "el.scrollIntoView({block:'center'});"
                + "var r=el.getBoundingClientRect();"
                + "return JSON.stringify({ok:true,"
                + "x:r.left+r.width/2,y:r.top+r.height/2});})()";
        // scrollIntoView 后等一拍让布局稳定，再取一次坐标
        eval(ptJs, 8000);
        Thread.sleep(250);
        JSONObject pt = new JSONObject(eval(ptJs, 8000));
        if (!pt.optBoolean("ok")) throw new Exception("browser_stale_ref: " + ref);
        final float cx = (float) pt.getDouble("x");
        final float cy = (float) pt.getDouble("y");
        String before = fingerprint();
        // 原生触摸：DOWN 立即派发、UP 延时约 90ms 再派发，时间戳与真实流逝一致
        // （0.6.2 把 UP 的未来时间戳与 DOWN 一起瞬间派发，疑似被输入管线丢弃）。
        final CountDownLatch touchDone = new CountDownLatch(1);
        onMain(new Callable<Boolean>() {
            @Override public Boolean call() {
                final WebView w = ensureWebView();
                float scale = w.getScale();
                final float vx = cx * scale, vy = cy * scale;
                final long now = android.os.SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, vx, vy, 0);
                w.dispatchTouchEvent(down);
                down.recycle();
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        MotionEvent up = MotionEvent.obtain(now, now + 90, MotionEvent.ACTION_UP, vx, vy, 0);
                        w.dispatchTouchEvent(up);
                        up.recycle();
                        touchDone.countDown();
                    }
                }, 90);
                return true;
            }
        }, 8000);
        touchDone.await(3, TimeUnit.SECONDS);
        Thread.sleep(800);
        JSONObject o = postAction(before);
        o.put("via", "touch");
        if (!o.optBoolean("changed")) {
            // 原生点击无反应 → JS click 兜底，结果里如实标注走的哪条路
            String js = "(function(){"
                    + "var el=document.querySelector('[data-muse-ref=\"" + ref + "\"]');"
                    + "if(!el)return JSON.stringify({ok:false});"
                    + "el.click();"
                    + "return JSON.stringify({ok:true});})()";
            JSONObject r = new JSONObject(eval(js, 8000));
            if (r.optBoolean("ok")) {
                Thread.sleep(800);
                JSONObject o2 = postAction(before);
                o2.put("via", "js-fallback");
                return o2;
            }
        }
        return o;
    }

    public JSONObject type(String ref, String text) throws Exception {
        if (wv == null) throw new Exception("browser_not_open: 先用 browser.open 打开页面");
        String before = fingerprint();
        String js = "(function(){"
                + "var el=document.querySelector('[data-muse-ref=\"" + ref + "\"]');"
                + "if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});"
                + "el.scrollIntoView({block:'center'});el.focus();"
                + "var text=" + JSONObject.quote(text == null ? "" : text) + ";"
                + "var tag=el.tagName.toLowerCase();"
                + "if(tag==='input'||tag==='textarea'){"
                + "var proto=tag==='input'?window.HTMLInputElement.prototype:window.HTMLTextAreaElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;"
                + "setter.call(el,text);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return JSON.stringify({ok:true,via:'setter',value:el.value});}"
                + "if(el.isContentEditable){"
                + "var sel=window.getSelection();sel.selectAllChildren(el);"
                + "var ok=document.execCommand('insertText',false,text);"
                + "return JSON.stringify({ok:true,via:'execCommand',dispatched:ok,"
                + "value:(el.innerText||'').slice(0,200)});}"
                + "return JSON.stringify({ok:false,reason:'not-editable',tag:tag});})()";
        JSONObject r = new JSONObject(eval(js, 10000));
        if (!r.optBoolean("ok")) {
            throw new Exception("browser_type_failed: " + r.optString("reason"));
        }
        Thread.sleep(400);
        JSONObject o = postAction(before);
        o.put("via", r.optString("via"));
        if (r.has("value")) o.put("value", r.optString("value"));
        if (r.has("dispatched")) o.put("dispatched", r.optBoolean("dispatched"));
        return o;
    }

    public JSONObject read(String ref) throws Exception {
        if (wv == null) throw new Exception("browser_not_open: 先用 browser.open 打开页面");
        if (ref != null && !ref.isEmpty()) {
            String js = "(function(){"
                    + "var el=document.querySelector('[data-muse-ref=\"" + ref + "\"]');"
                    + "if(!el)return JSON.stringify({ok:false,reason:'stale-ref'});"
                    + "var tag=el.tagName.toLowerCase();"
                    + "var o={ok:true,tag:tag,"
                    + "text:(el.innerText||'').replace(/\\n{3,}/g,'\\n\\n').slice(0,4000)};"
                    + "if(tag==='input'||tag==='textarea'){"
                    + "o.type=el.getAttribute('type')||'';o.value=el.value||'';"
                    + "o.placeholder=el.getAttribute('placeholder')||'';}"
                    + "return JSON.stringify(o);})()";
            JSONObject o = new JSONObject(eval(js, 10000));
            if (o.has("text")) o.put("text", capBytes(o.getString("text"), 4200));
            if (o.has("value")) o.put("value", capBytes(o.getString("value"), 1000));
            return o;
        }
        String js = "(function(){"
                + "var t=(document.body&&document.body.innerText)||'';"
                + "t=t.replace(/\\n{3,}/g,'\\n\\n');"
                + "return JSON.stringify({ok:true,title:document.title||'',"
                + "url:location.href,text:t.slice(0,8000)});})()";
        JSONObject o = new JSONObject(eval(js, 10000));
        if (o.has("text")) {
            String capped = capBytes(o.getString("text"), 4200);
            if (capped.length() < o.getString("text").length()) o.put("truncated", true);
            o.put("text", capped);
        }
        return o;
    }

    /** 按 UTF-8 字节预算截断字符串（中继通道体积上限约 7KB，留足余量）。 */
    private static String capBytes(String s, int maxBytes) {
        if (s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (s.substring(0, mid).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo);
    }

    // ---------------- 内部 ----------------

    private String fingerprint() {
        try {
            JSONObject o = new JSONObject(eval(FP_JS, 8000));
            return o.optString("fp");
        } catch (Exception e) {
            return "";
        }
    }

    /** 动作后置校验：页面指纹变没变 + 与上一轮快照的差分摘要。 */
    private JSONObject postAction(String beforeFp) throws Exception {
        String afterFp = fingerprint();
        JSONObject o = new JSONObject();
        o.put("changed", beforeFp.isEmpty() || !beforeFp.equals(afterFp));
        try {
            JSONObject snap = snapshotFull(false);
            if (snap.has("diff")) {
                JSONObject d = snap.getJSONObject("diff");
                o.put("diff", d);
                JSONArray addedRefs = d.optJSONArray("added");
                if (addedRefs != null && addedRefs.length() > 0) {
                    // 把新增节点的可见文字带回去，AI 不必再拍一轮
                    JSONArray names = new JSONArray();
                    JSONArray nodes = snap.optJSONArray("nodes");
                    for (int i = 0; i < nodes.length() && names.length() < 8; i++) {
                        JSONObject n = nodes.getJSONObject(i);
                        for (int j = 0; j < addedRefs.length(); j++) {
                            if (addedRefs.getString(j).equals(n.getString("ref"))) {
                                String nm = n.optString("name");
                                if (!nm.isEmpty()) names.put(nm);
                            }
                        }
                    }
                    o.put("newNames", names);
                }
            }
            o.put("url", snap.optString("url"));
        } catch (Exception ignored) {
        }
        return o;
    }
}
