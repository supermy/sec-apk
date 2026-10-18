package com.selfhost.layasec;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯逻辑单元测试:JVM 直跑(无需 Gradle/JUnit/设备),用法: bash run-tests.sh
 * 覆盖:IP 校验、SSH 告警解析、目标配置读写与容错、事件流配色、爆破字典约束、banner 探测失败路径。
 */
public class CoreLogicTest {

  private static int pass = 0, fail = 0;

  public static void main(String[] args) {
    testValidIp();
    testParseAlerts();
    testParseAlertsEmpty();
    testTargetStoreRoundtrip();
    testTargetStoreMissing();
    testTargetStoreBadJson();
    testTargetStoreEmptyHost();
    testFeedColorMapping();
    testBruteDictionaryBounds();
    testBruteStopFlag();
    testBruteBlockedStop();
    testProbeBannerNoListener();
    System.out.println("\n== 结果: " + pass + " 通过 / " + fail + " 失败 ==");
    if (fail > 0) System.exit(1);
  }

  private static void testValidIp() {
    ok(SshOps.validIp("127.0.0.1"), "validIp v4");
    ok(SshOps.validIp("45.148.10.7"), "validIp v4 public");
    ok(SshOps.validIp("2001:db8::1"), "validIp v6");
    ok(SshOps.validIp("::1"), "validIp v6 loopback");
    ok(!SshOps.validIp(""), "invalidIp empty");
    ok(!SshOps.validIp(null), "invalidIp null");
    ok(!SshOps.validIp("12"), "invalidIp short");
    ok(!SshOps.validIp("not an ip"), "invalidIp spaces");
    ok(!SshOps.validIp("ban me; rm -rf /"), "invalidIp injection chars");
  }

  private static void testParseAlerts() {
    SshOps ops = new SshOps(null);
    String block = String.join("\n",
        "ALERT_COUNT=7",
        "Failed password for root from 45.148.10.7 port 51822 ssh2",
        "Invalid user admin from 45.148.10.7 port 51823 ssh2",
        "== 来源TOP ==",
        "  5 45.148.10.7",
        "  2 103.75.22.18");
    ops.parseAlerts(block);
    eq(7, ops.lastCount(), "parseAlerts count");
    eq("45.148.10.7", ops.lastTopIp(), "parseAlerts top ip");
    ok(ops.lastSample().contains("45.148.10.7"), "parseAlerts sample has ip");
  }

  private static void testParseAlertsEmpty() {
    SshOps ops = new SshOps(null);
    ops.parseAlerts("ALERT_COUNT=0\n== 来源TOP ==\n");
    eq(0, ops.lastCount(), "parseAlerts zero count");
    eq("", ops.lastTopIp(), "parseAlerts no top ip");
    eq("", ops.lastSample(), "parseAlerts no sample");
  }

  private static void testTargetStoreRoundtrip() {
    String p = tmp("sec-target-test.json");
    new File(p).delete();
    try {
      TargetStore.save(p, "10.29.57.59", 8022, "dev");
      String[] t = TargetStore.load(p);
      ok(t != null, "target load not null");
      eq("10.29.57.59", t[0], "target host");
      eq("8022", t[1], "target port");
      eq("dev", t[2], "target user");
      ok(new String(java.nio.file.Files.readAllBytes(new File(p).toPath()), StandardCharsets.UTF_8).contains("updated"),
          "target file has updated field");
    } catch (Exception e) {
      nok("target roundtrip threw " + e);
    } finally {
      new File(p).delete();
    }
  }

  private static void testTargetStoreMissing() {
    ok(TargetStore.load(tmp("no-such-target-file.json")) == null, "missing target file -> null");
  }

  private static void testTargetStoreBadJson() {
    String p = tmp("sec-target-bad.json");
    try (FileOutputStream fo = new FileOutputStream(p)) {
      fo.write("{not json".getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      nok("bad json fixture write failed");
      return;
    }
    ok(TargetStore.load(p) == null, "bad json -> null");
    new File(p).delete();
  }

  private static void testTargetStoreEmptyHost() {
    String p = tmp("sec-target-empty.json");
    try (FileOutputStream fo = new FileOutputStream(p)) {
      fo.write("{\"host\":\"\",\"port\":22,\"user\":\"x\"}".getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      nok("empty host fixture write failed");
      return;
    }
    ok(TargetStore.load(p) == null, "empty host -> null");
    new File(p).delete();
  }

  private static void testFeedColorMapping() {
    eq(Theme.AMBER, EventFeed.colorOf(SecLab.TYPE_SSH), "color SSH");
    eq(Theme.RED, EventFeed.colorOf(SecLab.TYPE_ATTACK), "color ATTACK");
    eq(Theme.GREEN, EventFeed.colorOf(SecLab.TYPE_DEFENSE), "color DEFENSE");
    eq(Theme.CYAN, EventFeed.colorOf(SecLab.TYPE_CMD), "color CMD");
    ok(EventFeed.colorOf("??") != EventFeed.colorOf(SecLab.TYPE_ATTACK), "color default distinct");
  }

  private static void testBruteDictionaryBounds() {
    ok(SshOps.DEFAULT_BRUTE_USERS.length >= 2, "brute users >= 2");
    ok(SshOps.DEFAULT_BRUTE_PWDS.length >= 2, "brute pwds >= 2");
    ok(SshOps.DEFAULT_BRUTE_USERS.length * SshOps.DEFAULT_BRUTE_PWDS.length <= 20,
        "brute dictionary <= attempt cap(20),防止字典膨胀");
  }

  private static void testProbeBannerNoListener() {
    SshOps ops = new SshOps(null);
    eq(null, ops.probeBanner("127.0.0.1", 19399), "probe no listener -> null");
  }

  private static void testBruteStopFlag() {
    List<String> lines = new ArrayList<>();
    SshOps ops = new SshOps(lines::add);
    ops.stopBrute();
    int tries = ops.bruteAttempts(cfgClosedPort(), new String[]{"a"}, new String[]{"b"}, 4);
    eq(0, tries, "stopBrute 预置 -> 0 次尝试");
    ok(String.join("\n", lines).contains("中止"), "stopBrute -> 播报中止");
  }

  private static void testBruteBlockedStop() {
    List<String> lines = new ArrayList<>();
    SshOps ops = new SshOps(lines::add);
    ops.resetBrute();
    int tries = ops.bruteAttempts(cfgClosedPort(), new String[]{"a", "b"}, new String[]{"x"}, 4);
    eq(2, tries, "连接被拒连续 2 次 -> 自动停止");
    ok(String.join("\n", lines).contains("疑似目标机 fail2ban"), "blocked 分支播报");
  }

  private static SshOps.Cfg cfgClosedPort() {
    SshOps.Cfg c = new SshOps.Cfg();
    c.host = "127.0.0.1";
    c.port = 19399;
    c.user = "a";
    return c;
  }

  private static String tmp(String name) {
    return new File(System.getProperty("java.io.tmpdir"), name).getAbsolutePath();
  }

  private static void ok(boolean cond, String name) {
    if (cond) { pass++; System.out.println("  PASS " + name); }
    else { fail++; System.out.println("  FAIL " + name); }
  }

  private static void nok(String name) { fail++; System.out.println("  FAIL " + name); }

  private static void eq(Object expect, Object actual, String name) {
    boolean cond = expect == null ? actual == null : expect.equals(actual);
    if (cond) { pass++; System.out.println("  PASS " + name); }
    else { fail++; System.out.println("  FAIL " + name + "(期望=" + expect + " 实际=" + actual + ")"); }
  }
}
