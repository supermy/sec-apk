package com.selfhost.layasec;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 演练目标机配置(/sdcard/sec-lab/ssh-target.json):可外部编辑,App 启动自动载入;密码不落盘。 */
final class TargetStore {

  static final String FILE = "/sdcard/sec-lab/ssh-target.json";

  private TargetStore() {}

  /** 返回 {host, port, user};无配置或解析失败返回 null */
  static String[] load() { return load(FILE); }

  /** 指定路径版本(测试用同一逻辑) */
  static String[] load(String path) {
    try {
      File f = new File(path);
      if (!f.isFile()) return null;
      JSONObject j = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      String host = j.optString("host", "");
      if (host.isEmpty()) return null;
      return new String[]{host, String.valueOf(j.optInt("port", 22)), j.optString("user", "")};
    } catch (Throwable t) {
      return null;
    }
  }

  static void save(String host, int port, String user) throws Exception { save(FILE, host, port, user); }

  static void save(String path, String host, int port, String user) throws Exception {
    File dir = new File(path).getParentFile();
    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建目录 " + dir);
    JSONObject j = new JSONObject();
    j.put("host", host);
    j.put("port", port);
    j.put("user", user);
    j.put("updated", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
    try (FileOutputStream fo = new FileOutputStream(path)) {
      fo.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
    }
  }
}
