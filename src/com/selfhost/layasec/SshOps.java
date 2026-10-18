package com.selfhost.layasec;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSH 目标机防护演习(仅限自有/已授权设备,只读检查 + 防御配置,不含任何攻击动作):
 *   连接测试 / 防护现状检查 / 部署 fail2ban 实验规则(5 次/600s 封 600s)/ 拉取失败登录告警 / 封禁·解封源 IP。
 * 爆破模拟仍只指向本机回环靶标;本模块用于在自有服务器上完成「告警 → 分诊 → 处置」的防御闭环演练。
 */
public class SshOps {

  public interface Emit {
    void line(String text);
    /** 在目标机上实际执行的命令(供事件流展示,带 $ 前缀的脚本) */
    default void cmd(String command) {}
  }

  private static final Pattern IP_RE = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");
  private static final Pattern TOP_IP_RE = Pattern.compile("^\\s*(\\d+)\\s+([0-9a-fA-F:.]+)\\s*$");
  private static final Pattern COUNT_RE = Pattern.compile("ALERT_COUNT=(\\d+)");

  private final Emit emit;
  private volatile String lastTopIp = "";
  private volatile int lastCount = 0;
  private volatile String lastSample = "";
  /** 「停止全部」置位:口令尝试在每次尝试前检查,置位后立即中止(由 resetBrute 在每次新运行前清除) */
  private volatile boolean bruteStopped = false;

  public static class Cfg {
    public String host = "";
    public int port = 22;
    public String user = "";
    public String pass = "";
  }

  public SshOps(Emit emit) { this.emit = emit; }

  public String lastTopIp() { return lastTopIp; }
  public int lastCount() { return lastCount; }
  public String lastSample() { return lastSample; }

  public static boolean validIp(String ip) {
    return ip != null && !ip.isEmpty() && ip.length() <= 45 && IP_RE.matcher(ip).matches();
  }

  private void out(String s) { Emit e = emit; if (e != null) e.line(s); }

  private Session open(Cfg c) throws Exception {
    JSch j = new JSch();
    Session s = j.getSession(c.user, c.host, c.port);
    s.setPassword(c.pass.getBytes(StandardCharsets.UTF_8));
    Properties p = new Properties();
    p.put("StrictHostKeyChecking", "no"); // 教学演习:不做主机指纹校验
    s.setConfig(p);
    s.setTimeout(8000);
    s.connect(8000);
    return s;
  }

  private String exec(Session s, String cmd, int timeoutMs, int[] rcOut) throws Exception {
    ChannelExec ch = (ChannelExec) s.openChannel("exec");
    ch.setCommand(cmd);
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    ch.setErrStream(err);
    ch.connect(5000);
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    InputStream in = ch.getInputStream();
    byte[] buf = new byte[4096];
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      while (in.available() > 0) {
        int n = in.read(buf);
        if (n < 0) break;
        outBuf.write(buf, 0, n);
        if (outBuf.size() > 262144) break;
      }
      if (ch.isClosed()) break;
      if (System.currentTimeMillis() > deadline) { ch.disconnect(); out("\n[超时] 命令执行超时,已断开"); break; }
      Thread.sleep(60);
    }
    if (rcOut != null) {
      try { rcOut[0] = ch.getExitStatus(); } catch (Throwable ignored) {}
    }
    String o = outBuf.toString("UTF-8");
    String e = err.toString("UTF-8").trim();
    if (!e.isEmpty()) o = o + (o.isEmpty() || o.endsWith("\n") ? "" : "\n") + "[stderr] " + e + "\n";
    return o;
  }

  private void close(Session s) { try { if (s != null) s.disconnect(); } catch (Throwable ignored) {} }

  private void emitLines(String block) {
    for (String line : block.split("\n")) out(line);
  }

  private boolean withSession(Cfg c, int timeoutMs, String cmd) {
    return withSession(c, timeoutMs, cmd, false);
  }

  private boolean withSession(Cfg c, int timeoutMs, String cmd, boolean needOk) {
    Session s = null;
    try {
      out("SSH " + c.user + "@" + c.host + ":" + c.port + " …");
      s = open(c);
      Emit e0 = emit;
      if (e0 != null) e0.cmd("在 " + c.host + " 执行:\n$ " + cmd.replace("\n", "\n$ "));
      int[] rc = new int[]{0};
      String result = exec(s, cmd, timeoutMs, rc);
      emitLines(result.trim());
      return !needOk || rc[0] == 0;
    } catch (Throwable t) {
      out("SSH 失败: " + t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : ""));
      return false;
    } finally {
      close(s);
    }
  }

  // ---------------- 只读:连接测试 ----------------

  public boolean test(Cfg c) {
    return withSession(c, 15000,
        "echo '== 连接正常 =='; uname -srm; hostname; id -un 2>/dev/null || whoami");
  }

  // ---------------- 只读:防护现状检查 ----------------

  public boolean checkDefense(Cfg c) {
    String cmd = String.join("\n",
        "echo '== 主机 =='",
        "uname -srm; grep -E '^(NAME|VERSION)=' /etc/os-release 2>/dev/null",
        "echo '== sshd 关键配置 =='",
        "for k in Port PermitRootLogin PasswordAuthentication MaxAuthTries; do",
        "  grep -iE \"^[[:space:]]*$k[[:space:]]\" /etc/ssh/sshd_config 2>/dev/null | head -1",
        "done",
        "echo '== 近期失败登录(计数) =='",
        "( { journalctl -u ssh -u sshd --no-pager -n 400 2>/dev/null || cat /var/log/auth.log 2>/dev/null || cat /var/log/secure 2>/dev/null; } | grep -acE 'Failed password|Invalid user|authentication failure' ) || true",
        "echo '== fail2ban =='",
        "if command -v fail2ban-client >/dev/null 2>&1; then",
        "  (sudo -n fail2ban-client status sshd 2>&1 || fail2ban-client status sshd 2>&1) | sed -n '1,12p'",
        "else echo '未安装 fail2ban(Debian/Ubuntu: sudo apt-get install -y fail2ban)'; fi");
    return withSession(c, 30000, cmd);
  }

  // ---------------- 防御配置:部署 fail2ban 实验规则 ----------------

  public boolean deployLab(Cfg c) {
    String cmd = String.join("\n",
        "if ! command -v fail2ban-client >/dev/null 2>&1; then",
        "  echo '未安装 fail2ban:Debian/Ubuntu → sudo apt-get install -y fail2ban;RHEL → sudo dnf install -y fail2ban'; exit 0",
        "fi",
        "S=''; [ \"$(id -u)\" != 0 ] && S='sudo -n'",
        "$S sh -c 'printf \"[sshd]\\nenabled = true\\nmaxretry = 5\\nfindtime = 600\\nbantime = 600\\n\" > /etc/fail2ban/jail.d/sshd-lab.local' && echo '已写入 /etc/fail2ban/jail.d/sshd-lab.local(5 次/600s 封 600s)'",
        "($S systemctl restart fail2ban 2>/dev/null || $S service fail2ban restart 2>/dev/null) && echo 'fail2ban 已重启'",
        "$S fail2ban-client status sshd 2>&1 | sed -n '1,10p'");
    return withSession(c, 45000, cmd);
  }

  // ---------------- 拉取告警(只读) ----------------

  public boolean fetchAlerts(Cfg c) {
    lastTopIp = "";
    lastCount = 0;
    lastSample = "";
    String cmd = String.join("\n",
        "L=$( { journalctl -u ssh -u sshd --no-pager -n 400 2>/dev/null || cat /var/log/auth.log 2>/dev/null || cat /var/log/secure 2>/dev/null; } | grep -aE 'Failed password|Invalid user|authentication failure' | tail -60 )",
        "N=$(printf '%s\\n' \"$L\" | grep -c .)",
        "echo \"ALERT_COUNT=$N\"",
        "printf '%s\\n' \"$L\" | tail -20",
        "echo '== 来源TOP =='",
        "printf '%s\\n' \"$L\" | grep -aoE 'from ([0-9]{1,3}\\.){3}[0-9]{1,3}' | awk '{print $2}' | sort | uniq -c | sort -rn | head -5",
        "echo '== fail2ban 封禁 =='",
        "(sudo -n fail2ban-client status sshd 2>/dev/null || fail2ban-client status sshd 2>/dev/null) | sed -n '/Banned IP/,+2p' || true");

    Session session = null;
    boolean ok;
    try {
      out("SSH " + c.user + "@" + c.host + ":" + c.port + " …");
      session = open(c);
      Emit e0 = emit;
      if (e0 != null) e0.cmd("在 " + c.host + " 执行:\n$ " + cmd.replace("\n", "\n$ "));
      ChannelExec ch = (ChannelExec) session.openChannel("exec");
      ch.setCommand(cmd);
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      ch.setErrStream(err);
      ch.connect(5000);
      ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
      InputStream in = ch.getInputStream();
      byte[] buf = new byte[4096];
      long deadline = System.currentTimeMillis() + 30000;
      while (true) {
        while (in.available() > 0) {
          int n = in.read(buf);
          if (n < 0) break;
          outBuf.write(buf, 0, n);
          if (outBuf.size() > 262144) break;
        }
        if (ch.isClosed()) break;
        if (System.currentTimeMillis() > deadline) { ch.disconnect(); break; }
        Thread.sleep(60);
      }
      String block = outBuf.toString("UTF-8");
      emitLines(block.trim());
      parseAlerts(block);
      ok = true;
    } catch (Throwable t) {
      out("SSH 失败: " + t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : ""));
      ok = false;
    } finally {
      close(session);
    }
    return ok;
  }

  /** 解析 fetchAlerts 输出:ALERT_COUNT / 来源TOP / 样例(包可见便于单测) */
  void parseAlerts(String block) {
    Matcher mc = COUNT_RE.matcher(block);
    if (mc.find()) { try { lastCount = Integer.parseInt(mc.group(1)); } catch (Exception ignored) {} }
    String[] lines = block.split("\n");
    boolean inTop = false;
    for (String line : lines) {
      if (line.contains("== 来源TOP ==")) { inTop = true; continue; }
      if (inTop) {
        if (line.startsWith("==")) break;
        Matcher m = TOP_IP_RE.matcher(line);
        if (m.matches()) {
          String ip = m.group(2);
          if (validIp(ip) && lastTopIp.isEmpty()) lastTopIp = ip;
        }
      } else {
        String l = line.trim();
        if (!l.isEmpty() && !l.startsWith("ALERT_COUNT=") && (l.contains("Failed password") || l.contains("Invalid user") || l.contains("authentication failure"))) {
          lastSample = l.length() > 200 ? l.substring(0, 200) : l;
        }
      }
    }
  }

  // ---------------- 防御处置:封禁 / 解封 ----------------

  public boolean ban(Cfg c, String ip) {
    if (!validIp(ip)) { out("IP 格式不合法: " + ip); return false; }
    String cmd = "S=''; [ \"$(id -u)\" != 0 ] && S='sudo -n'; "
        + "$S fail2ban-client set sshd banip " + ip + " && echo '已封禁 " + ip + "(fail2ban)'";
    return withSession(c, 20000, cmd, true);
  }

  public boolean unban(Cfg c, String ip) {
    if (!validIp(ip)) { out("IP 格式不合法: " + ip); return false; }
    String cmd = "S=''; [ \"$(id -u)\" != 0 ] && S='sudo -n'; "
        + "$S fail2ban-client set sshd unbanip " + ip + " && echo '已解封 " + ip + "(fail2ban)'";
    return withSession(c, 20000, cmd, true);
  }

  // ---------------- SSH 靶标模式(演练页 ④⑤⑥;仅限自有/已授权设备) ----------------

  /** 目标机 sshd 端口 banner 探测(无需登录):成功返回 banner 行,不可达返回 null */
  public String probeBanner(String host, int port) {
    java.net.Socket s = null;
    try {
      s = new java.net.Socket();
      s.connect(new java.net.InetSocketAddress(host, port), 4000);
      s.setSoTimeout(4000);
      java.io.BufferedReader r = new java.io.BufferedReader(
          new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
      String line = r.readLine();
      return line == null ? "" : line.trim();
    } catch (Throwable t) {
      return null;
    } finally {
      try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }
  }

  public static final String[] DEFAULT_BRUTE_USERS = {"root", "admin", "test", "oracle"};
  public static final String[] DEFAULT_BRUTE_PWDS = {"123456", "password", "admin123", "12345678", "qwerty"};

  /** 每次新的口令尝试运行前调用(清除上次「停止全部」的残留标志) */
  public void resetBrute() { bruteStopped = false; }

  /** 「停止全部」:置位后正在进行的口令尝试将在下一次尝试前中止 */
  public void stopBrute() { bruteStopped = true; }

  /**
   * SSH 口令尝试(仅限自有/已授权设备的教学演习):直连目标机 sshd 按内置小字典逐一尝试,
   * 每次失败都真实写入目标机 sshd 日志并被 fail2ban 计数;连续出现连接被拒/超时
   * (通常意味着本机出口 IP 已被远端 fail2ban 封禁)则自动停止;stopBrute() 可随时中止。
   * 返回实际尝试次数。
   */
  public int bruteAttempts(Cfg c, String[] users, String[] pwds, int max) {
    int tries = 0, blocked = 0;
    out("SSH 靶标模式:对 " + c.host + ":" + c.port + " 发起口令尝试(内置小字典,上限 " + max + " 次)…");
    outer:
    for (String u : users) {
      for (String p : pwds) {
        if (bruteStopped) { out("收到停止请求:口令尝试已中止(已尝试 " + tries + " 次)"); return tries; }
        if (tries >= max) break outer;
        tries++;
        JSch j = new JSch();
        Session s = null;
        try {
          s = j.getSession(u, c.host, c.port);
          s.setPassword(p.getBytes(StandardCharsets.UTF_8));
          Properties pr = new Properties();
          pr.put("StrictHostKeyChecking", "no");
          s.setConfig(pr);
          s.setTimeout(4000);
          s.connect(4000);
          out("尝试 " + u + "/" + p + " … 登录成功!(存在弱口令,请立即更换口令)");
          return tries;
        } catch (com.jcraft.jsch.JSchException e) {
          String m = e.getMessage() == null ? "" : e.getMessage();
          if (m.contains("Auth fail") || m.contains("USERAUTH") || m.contains("auth")) {
            out("尝试 " + u + "/" + p + " … 失败(已写入目标机 sshd 失败日志)");
            blocked = 0;
          } else if (m.toLowerCase().contains("timeout") || m.contains("refused") || m.contains("Socket")) {
            blocked++;
            out("尝试 " + u + "/" + p + " … 连接被拒/超时 → 疑似目标机 fail2ban 已封禁本机出口 IP");
            if (blocked >= 2) { out("连续连接失败,停止口令尝试(远端防御已拦截)"); break outer; }
          } else {
            out("尝试 " + u + "/" + p + " … " + m);
          }
        } catch (Throwable t) {
          out("尝试 " + u + "/" + p + " … " + t.getClass().getSimpleName());
        } finally {
          close(s);
        }
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
      }
    }
    out("口令尝试结束:共 " + tries + " 次");
    return tries;
  }

  /** 读取远端 fail2ban 总状态与 sshd jail 封禁列表(被封禁后新 SSH 会话会失败,属预期) */
  public boolean defenseStatus(Cfg c) {
    String cmd = String.join("\n",
        "S=''; [ \"$(id -u)\" != 0 ] && S='sudo -n'",
        "if command -v fail2ban-client >/dev/null 2>&1; then",
        "  $S fail2ban-client status 2>&1 | sed -n '1,12p'",
        "  echo '== sshd jail =='",
        "  $S fail2ban-client status sshd 2>&1 | sed -n '1,12p'",
        "else echo '未安装 fail2ban(可先在「SSH目标」页③部署实验规则)'; fi");
    return withSession(c, 25000, cmd);
  }
}
