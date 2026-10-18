package com.selfhost.layasec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 ModelScope 公共仓库下载端侧模型(与 laya-test 共用 supermy/myjev,默认目录 sec2/phone):
 *   - 变体选择:MTK 天玑取 NPU 主图(laya_ml_s256_embeds_npu.tflite),其它机型取 GPU 主图(wfp16);
 *   - 已存在且字节数一致的文件自动跳过(有 Content-Length 时按长度校验);
 *   - 支持 .part 断点续传(Range),中断后再次点击继续拉取。
 * 仅做只读下载,不涉及任何外部目标操作。
 */
public class ModelNet {

  public static final String REPO_API = "https://modelscope.cn/api/v1/models/supermy/myjev/repo";
  public static final String DEFAULT_DIR = "sec2/phone";

  public static final String[] BASE_FILES = {
      "questions.json", "label.txt", "tokenizer.json", "token_embeddings.json",
      "token_embeddings_fp16.bin", "laya_ml_act_head_fp32.tflite",
      "laya_ml_scorer.bin", "laya_ml_calibration.json",
  };
  public static final String NPU_FILE = "laya_ml_s256_embeds_npu.tflite";
  public static final String GPU_FILE = "laya_ml_s256_embeds_wfp16.tflite";

  public interface Log {
    void line(String t);
    /** 总进度 0-100(可选实现) */
    default void percent(int p) {}
  }

  public static List<String> filesFor(boolean mtk) {
    List<String> l = new ArrayList<>();
    for (String f : BASE_FILES) l.add(f);
    l.add(mtk ? NPU_FILE : GPU_FILE);
    return l;
  }

  public static String url(String dir, String name) {
    return REPO_API + "?Revision=master&FilePath=" + dir + "/" + name;
  }

  /** 下载整套文件到 destDir(sec 源包目录)。返回 null 成功,否则错误串。须在后台线程调用。 */
  public static String download(File destDir, String dirInRepo, boolean mtk, Log log) {
    destDir.mkdirs();
    List<String> files = filesFor(mtk);
    int n = files.size();
    int done = 0, skipped = 0;
    for (int i = 0; i < n; i++) {
      String name = files.get(i);
      File f = new File(destDir, name);
      long total = contentLength(url(dirInRepo, name));
      if (f.isFile() && f.length() > 0 && (total <= 0 || f.length() == total)) {
        skipped++;
        log.line("[跳过] " + name + " 已完整(" + mb(f.length()) + ")");
        done++;
        log.percent((i + 1) * 100 / n);
        continue;
      }
      log.line("[下载] " + name + (total > 0 ? "(" + mb(total) + ")" : "") + " …");
      String err = fetch(url(dirInRepo, name), f, log, i, n);
      if (err != null) return "下载 " + name + " 失败: " + err;
      done++;
      log.percent((i + 1) * 100 / n);
    }
    log.line("模型文件就绪: " + done + " 个(其中跳过 " + skipped + " 个)→ " + destDir);
    log.line("提示: 再点「② 安装模型」拷入应用私有目录,或直接「③ 加载引擎」。");
    return null;
  }

  private static long contentLength(String spec) {
    HttpURLConnection c = null;
    try {
      c = open(spec);
      c.setRequestMethod("HEAD");
      long l = c.getContentLengthLong();
      if (l > 0) return l;
      c.disconnect();
      c = open(spec);
      l = c.getContentLengthLong();
      return l > 0 ? l : -1;
    } catch (Throwable t) {
      return -1;
    } finally {
      if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
    }
  }

  private static HttpURLConnection open(String spec) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    c.setRequestProperty("User-Agent", "seclab-apk/0.3 (Android; learning)");
    return c;
  }

  /** 下载单文件,支持 .part 断点续传;每推进约 32MB 输出一行进度与总进度百分比。 */
  private static String fetch(String spec, File dest, Log log, int idx, int n) {
    File part = new File(dest.getParentFile(), dest.getName() + ".part");
    long have = part.isFile() ? part.length() : 0;
    HttpURLConnection c = null;
    try {
      c = open(spec);
      if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
      int code = c.getResponseCode();
      long total = c.getContentLengthLong();
      boolean append = code == 206 && have > 0;
      if (!append) { have = 0; }
      if (append) total = have + total; // Content-Length 为剩余长度
      try (InputStream in = c.getInputStream();
           RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
        raf.seek(have);
        byte[] buf = new byte[65536];
        long now = have, lastLog = have;
        int nread;
        while ((nread = in.read(buf)) > 0) {
          raf.write(buf, 0, nread);
          now += nread;
          if (now - lastLog >= 32L * 1024 * 1024) {
            lastLog = now;
            log.line("  " + dest.getName() + " " + mb(now) + (total > 0 ? "/" + mb(total) + "(" + now * 100 / total + "%)" : ""));
            log.percent(overall(idx, n, now, total));
          }
        }
        raf.getFD().sync();
      }
      if (dest.isFile()) dest.delete();
      if (!part.renameTo(dest)) return "重命名失败";
      log.line("[完成] " + dest.getName() + " " + mb(dest.length()));
      return null;
    } catch (Throwable t) {
      return t.getClass().getSimpleName() + (t.getMessage() != null ? " " + t.getMessage() : "");
    } finally {
      if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
    }
  }

  private static int overall(int idx, int n, long now, long total) {
    double frac = total > 0 ? (double) now / total : 0;
    int p = (int) ((idx + frac) * 100 / n);
    return Math.max(0, Math.min(99, p));
  }

  private static String mb(long bytes) {
    return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
  }
}
