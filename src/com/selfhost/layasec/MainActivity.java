package com.selfhost.layasec;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 攻防实验台 — 非 Root 本机网络攻防教学:
 *   靶标(本地弱口令 HTTP 服务) + 攻击模拟(仅指向靶标) + fail2ban 式防御 + Laya NPU 告警分诊。
 */
public class MainActivity extends Activity implements SecLab.Listener {

  private static final int PRIMARY = 0xFF3E7BFA;
  private static final int PORT = 18080;

  private SecLab lab;
  private SecTriage triage;

  private TextView statusView;
  private TextView feedView;
  private ScrollView feedScroll;
  private EditText manualInput;

  private final List<String[]> events = new ArrayList<>(); // {type, text}
  private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    lab = new SecLab(this, PORT);
    lab.setListener(this);
    triage = new SecTriage(this);
    lab.setTriage(text -> {
      JSONObject r = triage.triage(text);
      return formatTriage(r);
    });
    buildUi();
    requestStorage();
    refreshStatus();
  }

  private void requestStorage() {
    if (Build.VERSION.SDK_INT >= 30) {
      if (!android.os.Environment.isExternalStorageManager()) {
        try {
          android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
          i.setData(android.net.Uri.parse("package:" + getPackageName()));
          startActivity(i);
        } catch (Exception ignored) {}
      }
    } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
    }
  }

  // ================= UI =================

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFFF5F5F5);

    TextView title = new TextView(this);
    title.setText(getString(R.string.app_name));
    title.setTextSize(17);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, dp(12), 0, dp(12));
    title.setTextColor(0xFF1A1A1A);
    title.setBackgroundColor(0xFFEDEDED);
    root.addView(title);

    statusView = new TextView(this);
    statusView.setTextSize(12);
    statusView.setTextColor(0xFF333333);
    statusView.setPadding(dp(12), dp(8), dp(12), dp(8));
    statusView.setBackgroundColor(0xFFFFFFFF);
    root.addView(statusView);

    LinearLayout btnBox = new LinearLayout(this);
    btnBox.setOrientation(LinearLayout.VERTICAL);
    btnBox.setPadding(dp(8), dp(8), dp(8), dp(4));
    addRow(btnBox, "安装模型", v -> bg(this::doInstall), "加载引擎(NPU)", v -> bg(this::doLoad));
    addRow(btnBox, "启动靶标", v -> bg(() -> run(lab::startTarget)), "模拟爆破", v -> bg(() -> { lab.startAttack(4); refreshStatus(); }));
    addRow(btnBox, "启动防御", v -> bg(() -> { lab.startDefender(); refreshStatus(); }), "一键演示", v -> bg(this::doDemo));
    addRow(btnBox, "停止全部", v -> bg(this::doStopAll), "清空封禁", v -> bg(() -> { lab.clearBans(); refreshStatus(); }));
    root.addView(btnBox);

    // 手动分诊
    LinearLayout manualRow = new LinearLayout(this);
    manualRow.setOrientation(LinearLayout.HORIZONTAL);
    manualRow.setPadding(dp(8), dp(2), dp(8), dp(6));
    manualInput = new EditText(this);
    manualInput.setHint("粘贴一段安全相关文本手动分诊…");
    manualInput.setTextSize(12);
    manualInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button mtBtn = new Button(this);
    mtBtn.setText("分诊");
    mtBtn.setAllCaps(false);
    mtBtn.setOnClickListener(v -> bg(() -> {
      String t = manualInput.getText().toString().trim();
      if (t.isEmpty()) return;
      String r = lab.classifyText(t);
      onEvent(SecLab.TYPE_TRIAGE, "手动分诊: " + r);
    }));
    manualRow.addView(manualInput);
    manualRow.addView(mtBtn);
    root.addView(manualRow);

    TextView feedCap = new TextView(this);
    feedCap.setText("事件流");
    feedCap.setTextSize(12);
    feedCap.setTypeface(Typeface.DEFAULT_BOLD);
    feedCap.setPadding(dp(12), dp(4), 0, dp(4));
    feedCap.setTextColor(0xFF666666);
    root.addView(feedCap);

    feedScroll = new ScrollView(this);
    feedView = new TextView(this);
    feedView.setTextSize(12);
    feedView.setTypeface(Typeface.MONOSPACE);
    feedView.setTextColor(0xFF222222);
    feedView.setPadding(dp(12), dp(4), dp(12), dp(24));
    feedScroll.addView(feedView);
    root.addView(feedScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    setContentView(root);
  }

  private interface Click { void onClick(View v); }

  private void addRow(LinearLayout parent, String l1, Click c1, String l2, Click c2) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, dp(3), 0, dp(3));
    Button b1 = makeBtn(l1); b1.setOnClickListener(c1::onClick);
    Button b2 = makeBtn(l2); b2.setOnClickListener(c2::onClick);
    row.addView(b1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    row.addView(b2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    parent.addView(row);
  }

  private Button makeBtn(String text) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setTextColor(PRIMARY);
    return b;
  }

  private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

  @Override
  public void onEvent(String type, String text) {
    runOnUiThread(() -> {
      events.add(new String[]{type, text});
      if (events.size() > 300) events.subList(0, 100).clear();
      renderFeed();
      refreshStatus();
    });
  }

  private int colorOf(String type) {
    switch (type) {
      case SecLab.TYPE_TARGET: return 0xFF1565C0;   // 蓝
      case SecLab.TYPE_ATTACK: return 0xFFC62828;   // 红
      case SecLab.TYPE_DEFENSE: return 0xFF2E7D32;  // 绿
      case SecLab.TYPE_TRIAGE: return 0xFF6A1B9A;   // 紫
      default: return 0xFF546E7A;                    // 灰蓝
    }
  }

  private void renderFeed() {
    SpannableStringBuilder sb = new SpannableStringBuilder();
    for (String[] e : events) {
      String line = "[" + tf.format(new Date()) + "] " + e[1] + "\n";
      int start = sb.length();
      sb.append(line);
      sb.setSpan(new ForegroundColorSpan(colorOf(e[0])), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    feedView.setText(sb);
    feedScroll.post(() -> feedScroll.fullScroll(View.FOCUS_DOWN));
  }

  private void refreshStatus() {
    if (statusView == null) return;
    String model = triage.modelReady() ? getString(R.string.model_ok)
        : (triage.sourceReady() ? "待安装(源包已就绪)" : getString(R.string.model_missing));
    StringBuilder sb = new StringBuilder();
    sb.append("模型: ").append(model).append("  ·  引擎: ").append(triage.backend()).append("\n");
    sb.append("靶标: ").append(lab.targetRunning() ? "运行中 :" + lab.port() : "已停止")
      .append("  ·  防御: ").append(lab.defenderRunning() ? "运行中(窗口" + lab.windowSec() + "s/阈值" + lab.threshold() + ")" : "已停止").append("\n");
    List<String> bans = lab.bannedIps();
    sb.append("封禁: ").append(bans.isEmpty() ? "无" : String.join(", ", bans));
    statusView.setText(sb.toString());
  }

  // ================= 后台动作 =================

  private interface Cmd { void run() throws Exception; }

  private void run(Cmd c) { run(0, c); }

  private void run(int delayMs, Cmd c) {
    new Thread(() -> {
      try { if (delayMs > 0) Thread.sleep(delayMs); c.run(); }
      catch (Throwable t) { onEvent(SecLab.TYPE_INFO, "错误: " + t.getMessage()); }
    }, "sec-ui-bg").start();
  }

  private void bg(Cmd c) { run(c); }

  private void doInstall() {
    if (!triage.sourceReady()) {
      onEvent(SecLab.TYPE_INFO, "源包缺失: " + SecTriage.SOURCE_DIR);
      return;
    }
    onEvent(SecLab.TYPE_INFO, "正在安装模型(约 650MB,稍候)…");
    String err = triage.install();
    onEvent(SecLab.TYPE_INFO, err == null ? "模型安装完成" : err);
  }

  private void doLoad() {
    if (!triage.modelReady()) {
      onEvent(SecLab.TYPE_INFO, "模型未安装,请先「安装模型」");
      return;
    }
    onEvent(SecLab.TYPE_INFO, "正在加载引擎(" + (triage.mtkNpuAvailable() ? "NPU" : "GPU/CPU") + ")…");
    String err = triage.load();
    onEvent(SecLab.TYPE_INFO, err == null ? "引擎就绪: " + triage.backend() : err);
  }

  private void doDemo() {
    if (!triage.modelReady()) {
      onEvent(SecLab.TYPE_INFO, "提示: 模型未安装,将仅演示攻防流程(无 NPU 分诊),可先点「安装模型」");
    } else if (!triage.isLoaded()) {
      onEvent(SecLab.TYPE_INFO, "正在加载引擎…");
      String err = triage.load();
      onEvent(SecLab.TYPE_INFO, err == null ? "引擎就绪: " + triage.backend() : err);
    }
    lab.runDemo(4);
    run(200, this::refreshStatus);
  }

  private void doStopAll() {
    lab.stopAttack();
    lab.stopDefender();
    lab.stopTarget();
    run(300, this::refreshStatus);
  }

  private String formatTriage(JSONObject r) {
    StringBuilder sb = new StringBuilder();
    sb.append("类别=").append(r.optString("category", "?"))
      .append("(").append(String.format(Locale.US, "%.2f", r.optDouble("categoryConf", 0))).append(")");
    sb.append(" 严重度=").append(String.format(Locale.US, "%.2f", r.optDouble("severity", 0))).append("/5");
    String lab2 = r.optString("severityLabel", "");
    if (!lab2.isEmpty()) sb.append("(").append(lab2).append(")");
    sb.append(" 人工研判=").append(String.format(Locale.US, "%.2f", r.optDouble("review", 0)));
    // 建议动作
    JSONObject rr = r;
    sb.append(" → ").append(suggest(rr));
    sb.append(" [").append(r.optString("backend", "")).append(" ").append(r.optLong("latencyMs")).append("ms]");
    return sb.toString();
  }

  private String suggest(JSONObject r) {
    String cat = r.optString("category");
    double sev = r.optDouble("severity", 0);
    double review = r.optDouble("review", 0);
    if (review >= 0.5 || sev >= 3.5) return "封禁IP并转人工研判";
    if ("攻击威胁".equals(cat) || "侦察探测".equals(cat) || sev >= 2.0) return "封禁IP(自动处置)";
    if ("钓鱼欺诈".equals(cat)) return "标记并从邮件网关拦截";
    if ("防御处置".equals(cat)) return "记录处置动作";
    return "放行/仅记录";
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    try { lab.stopAttack(); lab.stopDefender(); lab.stopTarget(); } catch (Throwable ignored) {}
    try { triage.close(); } catch (Throwable ignored) {}
  }
}