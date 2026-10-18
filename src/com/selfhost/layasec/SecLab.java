package com.selfhost.layasec;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 非 Root 本机攻防靶场(教学用):
 *   ① 靶标:内置 HTTP 登录服务(弱口令 testuser/test123),仅监听回环地址,每次请求写访问日志;
 *   ② 攻击方:模拟弱口令爆破(字典 + 并发),请求仅指向本机靶标;
 *   ③ 防御方:fail2ban 式监控(窗口内失败计数超阈值 → 封禁 IP),并调用 Laya NPU 决策模型分诊告警。
 * 全部限制在本机回环,不涉及任何外部目标。
 */
public class SecLab {

  public interface Listener { void onEvent(String type, String text); }

  /** 分诊钩子:传入告警文本,返回可展示的分诊结论;引擎未就绪时抛异常由调用方兜底。 */
  public interface Triage { String classify(String text) throws Exception; }

  public static final String TYPE_INFO = "INFO";
  public static final String TYPE_TARGET = "TARGET";
  public static final String TYPE_ATTACK = "ATTACK";
  public static final String TYPE_DEFENSE = "DEFENSE";
  public static final String TYPE_TRIAGE = "TRIAGE";
  /** 实际执行/等效命令(教学展示,事件流以青色 $ 行呈现) */
  public static final String TYPE_CMD = "CMD";
  /** SSH 目标机事件(与 SshOps.Emit 对应) */
  public static final String TYPE_SSH = "SSH";

  private final Context ctx;
  private final int port;
  private volatile Listener listener;
  private volatile Triage triage;

  // 靶标
  private ServerSocket server;
  private volatile boolean targetRunning;
  private final Map<String, String> users = new ConcurrentHashMap<>();  // user -> pass（故意弱口令）

  // 防御
  private final Map<String, ArrayDeque<Long>> fails = new ConcurrentHashMap<>();
  private final java.util.Set<String> banlist = ConcurrentHashMap.newKeySet();
  private volatile boolean defenderRunning;
  private volatile int windowSec = 10;
  private volatile int threshold = 5;
  private long windowStart = System.currentTimeMillis();

  // 访问事件队列(靶标 → 防御方)
  private final BlockingQueue<Access> queue = new LinkedBlockingQueue<>();

  // 攻击
  private volatile boolean attacking;
  private ExecutorService attackPool;
  /** 攻击代际:停止/重启时自增,用于废弃在途请求结果与旧监听线程日志 */
  private final AtomicInteger attackGen = new AtomicInteger();
  private final AtomicInteger attackIdx = new AtomicInteger(0);

  // 日志文件
  private final File accessFile;
  private final File defenseFile;
  private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
  private final SimpleDateFormat full = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

  static class Access {
    String ip, user, result;
    boolean banned;
    long ts = System.currentTimeMillis();
  }

  public SecLab(Context ctx, int port) {
    this.ctx = ctx.getApplicationContext();
    this.port = port;
    users.put("testuser", "test123"); // 故意弱口令
    File dir = ctx.getExternalFilesDir(null);
    if (dir == null) dir = ctx.getFilesDir();
    accessFile = new File(dir, "access.log");
    defenseFile = new File(dir, "defense.log");
  }

  public void setListener(Listener l) { this.listener = l; }
  public void setTriage(Triage t) { this.triage = t; }
  public int port() { return port; }
  public boolean targetRunning() { return targetRunning; }
  public boolean defenderRunning() { return defenderRunning; }
  public boolean attacking() { return attacking; }
  /** 爆破进度(已发出的候选数)/总数,供 UI 目视 */
  public int attackProgress() { return Math.min(attackIdx.get(), WORDS.length); }
  public int attackTotal() { return WORDS.length; }
  public void setPolicy(int windowSec, int threshold) { this.windowSec = windowSec; this.threshold = threshold; }
  public int windowSec() { return windowSec; }
  public int threshold() { return threshold; }
  public List<String> bannedIps() { return new ArrayList<>(banlist); }
  public void clearBans() {
    banlist.clear();
    fails.clear();
    emit(TYPE_INFO, "已清空封禁列表");
    emit(TYPE_CMD, "$ fail2ban-client unban --all   # 等效(本靶标为应用层封禁,已全部解除)");
  }

  private void emit(String type, String text) {
    Listener l = listener;
    if (l != null) {
      try { l.onEvent(type, text); } catch (Throwable ignored) {}
    }
  }

  // ================= 靶标 =================

  public String startTarget() {
    if (targetRunning) return null;
    try {
      server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
    } catch (Exception e) {
      return "靶标启动失败: " + e.getMessage();
    }
    targetRunning = true;
    Thread t = new Thread(this::acceptLoop, "sec-target");
    t.setDaemon(true); t.start();
    emit(TYPE_TARGET, "靶标已启动 http://127.0.0.1:" + port + "/login(弱口令 testuser/test123,仅本机)");
    emit(TYPE_CMD, "$ curl -s http://127.0.0.1:" + port + "/login   # 探活(应用内置弱口令靶标,仅监听回环)");
    return null;
  }

  public void stopTarget() {
    targetRunning = false;
    try { if (server != null) server.close(); } catch (Exception ignored) {}
    server = null;
    emit(TYPE_INFO, "靶标已停止");
  }

  private void acceptLoop() {
    while (targetRunning) {
      Socket s;
      try { s = server.accept(); } catch (Exception e) { break; }
      Thread h = new Thread(() -> handle(s), "sec-target-conn");
      h.setDaemon(true); h.start();
    }
  }

  private void handle(Socket s) {
    String ip = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "?";
    try {
      BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
      String reqLine = in.readLine();
      if (reqLine == null) { s.close(); return; }
      String[] parts = reqLine.split(" ");
      String method = parts.length > 0 ? parts[0] : "GET";
      String path = parts.length > 1 ? parts[1] : "/";
      int len = 0;
      String line;
      while ((line = in.readLine()) != null && !line.isEmpty()) {
        String lc = line.toLowerCase(Locale.US);
        if (lc.startsWith("content-length:")) {
          try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
        }
      }
      StringBuilder body = new StringBuilder();
      if (len > 0) {
        char[] buf = new char[len];
        int off = 0;
        while (off < len) {
          int r = in.read(buf, off, len - off);
          if (r < 0) break;
          off += r;
        }
        body.append(buf, 0, off);
      }

      if (isBanned(ip)) {
        logAccess(ip, "-", "BLOCKED", true);
        respond(s, 403, "{\"error\":\"banned\"}");
        return;
      }

      if (method.equals("POST") && path.startsWith("/login")) {
        String user = "", pw = "";
        try {
          JSONObject j = new JSONObject(body.toString());
          user = j.optString("user", "");
          pw = j.optString("password", "");
        } catch (Exception ignored) {}
        boolean ok = pw.length() > 0 && pw.equals(users.get(user));
        logAccess(ip, user.isEmpty() ? "-" : user, ok ? "OK" : "FAIL", false);
        if (ok) respond(s, 200, "{\"status\":\"ok\",\"token\":\"sess-" + Long.toHexString(System.nanoTime()) + "\"}");
        else respond(s, 401, "{\"status\":\"fail\"}");
      } else {
        respond(s, 200, "{\"status\":\"login required\",\"hint\":\"POST /login {user,password}\"}");
      }
    } catch (Exception e) {
      Log.w("SecLab", "handle: " + e.getMessage());
    } finally {
      try { s.close(); } catch (Exception ignored) {}
    }
  }

  private void respond(Socket s, int code, String json) {
    try {
      byte[] b = json.getBytes(StandardCharsets.UTF_8);
      OutputStream os = s.getOutputStream();
      String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : "Error") + "\r\n"
          + "Content-Type: application/json\r\n"
          + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
      os.write(head.getBytes(StandardCharsets.UTF_8));
      os.write(b);
      os.flush();
    } catch (Exception ignored) {}
  }

  private boolean isBanned(String ip) { return banlist.contains(ip); }

  private void logAccess(String ip, String user, String result, boolean banned) {
    Access a = new Access();
    a.ip = ip; a.user = user; a.result = result; a.banned = banned;
    queue.offer(a);
    append(accessFile, full.format(new Date()) + " " + ip + " user=" + user + " result=" + result + " banned=" + banned);
  }

  private synchronized void append(File f, String line) {
    try (FileOutputStream fos = new FileOutputStream(f, true)) {
      fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {}
  }

  // ================= 防御方(fail2ban 式) =================

  public void startDefender() {
    if (defenderRunning) return;
    defenderRunning = true;
    Thread t = new Thread(this::defenderLoop, "sec-defender");
    t.setDaemon(true); t.start();
    emit(TYPE_DEFENSE, "防御方已启动:窗口 " + windowSec + "s 内失败 ≥ " + threshold + " 次即封禁");
    emit(TYPE_CMD, "$ fail2ban-client set sshd maxretry " + threshold + " findtime " + windowSec + " bantime " + (windowSec * 6)
        + "   # 等效(应用层计数封禁,规则同 fail2ban)");
  }

  public void stopDefender() {
    defenderRunning = false;
    emit(TYPE_INFO, "防御方已停止");
  }

  private void defenderLoop() {
    while (defenderRunning) {
      Access a;
      try { a = queue.poll(400, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { break; }
      if (a == null) continue;
      long now = System.currentTimeMillis();
      if (a.banned) { emit(TYPE_DEFENSE, "拦截: 被封禁 IP " + a.ip + " 再请求(403)"); continue; }
      if ("OK".equals(a.result)) {
        fails.remove(a.ip);
        continue;
      }
      if (!"FAIL".equals(a.result)) continue;
      ArrayDeque<Long> dq = fails.computeIfAbsent(a.ip, k -> new ArrayDeque<>());
      synchronized (dq) {
        long cutoff = now - windowSec * 1000L;
        while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.pollFirst();
        dq.addLast(now);
        int n = dq.size();
        emit(TYPE_DEFENSE, "失败登录 #" + n + " ip=" + a.ip + " user=" + a.user);
        if (n >= threshold && !isBanned(a.ip)) {
          ban(a.ip, n + " 次失败登录/" + windowSec + "s");
        }
      }
    }
  }

  private void ban(String ip, String reason) {
    banlist.add(ip);
    String line = tf.format(new Date()) + " BAN " + ip + " (" + reason + ")";
    append(defenseFile, line);
    emit(TYPE_DEFENSE, "★ 封禁 " + ip + "(" + reason + ")");
    emit(TYPE_CMD, "$ iptables -A INPUT -s " + ip + " -j DROP   # fail2ban 等效动作(非 root 环境以应用层封禁模拟)");
    // Laya NPU 告警分诊
    Triage tr = triage;
    if (tr != null) {
      String text = "防御告警: 来源 IP " + ip + " 在 " + windowSec + " 秒内对登录接口 /login 连续失败 "
          + threshold + " 次(用户名 testuser),短时间高频失败登录,疑似口令爆破/侦察探测。";
      try {
        String verdict = tr.classify(text);
        emit(TYPE_TRIAGE, "分诊[NPU]: " + verdict);
      } catch (Throwable t) {
        emit(TYPE_TRIAGE, "分诊失败(引擎未就绪): " + t.getMessage());
      }
    }
  }

  // ================= 攻击方(仅指向本机靶标) =================

  private static final String[] WORDS = {
      "123456", "password", "12345678", "qwerty", "abc123",
      "monkey", "letmein", "dragon", "111111", "baseball",
      "iloveyou", "trustno1", "sunshine", "master", "welcome",
      "shadow", "ashley", "football", "jesus", "michael",
      "ninja", "mustang", "password1", "admin123", "root123",
      "toor", "pass123", "qwe123", "test123", // 真实弱口令
      "zxcvbnm", "asdfgh", "123qwe", "1q2w3e4r", "aa123456",
  };

  public void startAttack(int concurrency) {
    if (attacking) return;
    if (!targetRunning) { emit(TYPE_INFO, "请先启动靶标"); return; }
    attacking = true;
    attackIdx.set(0);
    final int gen = attackGen.incrementAndGet();
    final String url = "http://127.0.0.1:" + port + "/login";
    emit(TYPE_ATTACK, "开始模拟弱口令爆破(" + WORDS.length + " 个候选,并发 " + concurrency + ")");
    emit(TYPE_CMD, "$ for pw in <" + WORDS.length + " 个弱口令字典>; do curl -s -X POST " + url
        + " -H 'Content-Type: application/json' -d '{\"user\":\"testuser\",\"password\":\"'$pw'\"}'; done"
        + "   # 本应用内置等效请求,仅指向本机靶标");
    attackPool = Executors.newFixedThreadPool(Math.max(1, concurrency));
    final AtomicInteger idx = new AtomicInteger(0);
    final AtomicInteger hits = new AtomicInteger(0);
    final AtomicInteger blocked = new AtomicInteger(0);
    for (int i = 0; i < Math.max(1, concurrency); i++) {
      attackPool.submit(() -> {
        while (attacking && gen == attackGen.get()) {
          int k = idx.getAndIncrement();
          if (k >= WORDS.length) break;
          attackIdx.set(Math.min(k + 1, WORDS.length));
          String pw = WORDS[k];
          try {
            int code = postLogin(url, pw);
            if (gen != attackGen.get()) break; // 已停止:丢弃在途请求结果,不再记录日志
            String req = "POST /login password=\"" + pw + "\"";
            if (code == 200) { hits.incrementAndGet(); emit(TYPE_ATTACK, "命中 " + pw + " → 200 OK"); }
            else if (code == 403) { blocked.incrementAndGet(); emit(TYPE_ATTACK, req + " → 403(IP 已被 fail2ban 式封禁)"); }
            else emit(TYPE_ATTACK, req + " → " + code);
          } catch (Exception e) {
            if (gen == attackGen.get()) emit(TYPE_ATTACK, pw + " → 错误: " + e.getMessage());
          }
        }
      });
    }
    Thread watch = new Thread(() -> {
      try { attackPool.shutdown(); attackPool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
      if (gen != attackGen.get()) return; // 已被「停止全部」终止,不再播报
      attacking = false;
      emit(TYPE_INFO, "爆破结束: 命中 " + hits.get() + ", 被拦截 " + blocked.get() + "/" + WORDS.length);
    }, "sec-attack-watch");
    watch.setDaemon(true); watch.start();
  }

  public void stopAttack() {
    if (!attacking) { emit(TYPE_INFO, "爆破未在运行"); return; }
    attacking = false;
    attackGen.incrementAndGet(); // 废弃在途请求与监听线程的后续日志
    if (attackPool != null) attackPool.shutdownNow();
    emit(TYPE_INFO, "已停止爆破(已发出 " + attackIdx.get() + "/" + WORDS.length + " 次,在途请求结果不再记录)");
  }

  private int postLogin(String url, String pw) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setConnectTimeout(4000);
    c.setReadTimeout(6000);
    c.setRequestProperty("Content-Type", "application/json");
    byte[] data = ("{\"user\":\"testuser\",\"password\":\"" + pw + "\"}").getBytes(StandardCharsets.UTF_8);
    c.setFixedLengthStreamingMode(data.length);
    try (OutputStream os = c.getOutputStream()) { os.write(data); }
    return c.getResponseCode();
  }

  // ================= 一键演示 =================

  public void runDemo(int concurrency) {
    clearBans();
    append(accessFile, "==== demo " + full.format(new Date()) + " ====");
    String err = startTarget();
    if (err != null) { emit(TYPE_INFO, err); return; }
    if (!defenderRunning) startDefender();
    startAttack(concurrency);
  }

  // ================= 单条文本分诊(手动) =================

  public String classifyText(String text) {
    Triage tr = triage;
    if (tr == null) return "分诊未就绪(引擎未加载)";
    try {
      return tr.classify(text);
    } catch (Throwable t) {
      return "分诊失败: " + t.getMessage();
    }
  }
}