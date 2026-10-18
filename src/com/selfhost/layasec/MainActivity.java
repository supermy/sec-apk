package com.selfhost.layasec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import kotlin.Pair;

/**
 * 攻防实验台 — 非 Root 本机网络攻防教学:
 *   靶标(本地弱口令 HTTP 服务) + 攻击模拟(仅指向靶标) + fail2ban 式防御 + Laya NPU 告警分诊 + SSH 防护演习。
 * 布局:标题 → Tab(演练/分诊/SSH) → Tab 内容 → 常驻事件流(单屏可见);步骤按钮带完成状态与实际命令($ 行)。
 */
public class MainActivity extends Activity implements SecLab.Listener {

  private static final int PORT = 18080;
  private static final String TYPE_SSH = SecLab.TYPE_SSH;
  private static final String PREFS = "sec-lab";

  // 主题色(集中定义于 Theme;此处保留短名,布局代码照旧引用)
  private static final int BG = Theme.BG;
  private static final int TXT = Theme.TXT;
  private static final int DIM = Theme.DIM;
  private static final int OFF = Theme.OFF;
  private static final int PRIMARY = Theme.PRIMARY;
  private static final int CYAN = Theme.CYAN;
  private static final int GREEN = Theme.GREEN;
  private static final int RED = Theme.RED;
  private static final int AMBER = Theme.AMBER;

  private SecLab lab;
  private SecTriage triage;
  private SshOps ssh;
  private SharedPreferences prefs;

  private TextView statusLine1, statusLine2, statusLine3, statusLine4;
  private TextView feedView;
  private ScrollView feedScroll;
  private EditText manualInput;
  private EditText sshHost, sshPort, sshUser, sshPass;
  private TextView sshTargetLine;

  private Button btnNet, btnInstall, btnLoad, btnTarget, btnAttack, btnDefender, btnDemo;
  private Button btnSshTest, btnSshCheck, btnSshDeploy, btnSshAlerts, btnSshBan, btnSshUnban;
  private ProgressBar progressBar;
  private final TextView[] tabs = new TextView[3];
  private final View[] tabViews = new View[3];
  private volatile boolean attackDone = false;
  private volatile boolean sshTestOk, sshCheckOk, sshDeployOk, sshAlertsOk, sshBanOk, sshUnbanOk;
  private volatile String lastTopIp = "";

  private EventFeed feed;

  /** 应用内语言(zh/en/跟随系统):覆写资源上下文 */
  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(Lang.wrap(base));
  }

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    // 测试/演示钩子:am start ... --es lang en|zh|system(冷启动生效)
    if (getIntent() != null) {
      String want = getIntent().getStringExtra("lang");
      if (want != null) {
        if ("system".equals(want)) want = "";
        if ((want.isEmpty() || "zh".equals(want) || "en".equals(want)) && !want.equals(Lang.current(this))) {
          Lang.set(this, want);
          return;
        }
      }
    }
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    ssh = new SshOps(new SshOps.Emit() {
      @Override public void line(String t) { onEvent(TYPE_SSH, t); }
      @Override public void cmd(String c) { onEvent(SecLab.TYPE_CMD, c); }
    });
    lab = new SecLab(this, PORT);
    lab.setListener(this);
    triage = new SecTriage(this);
    lab.setTriage(text -> formatTriage(triage.triage(text)));
    buildUi();
    requestStorage();
    refreshStatus();
    if (!prefs.getBoolean("guide_shown", false)) {
      prefs.edit().putBoolean("guide_shown", true).apply();
      feedScroll.postDelayed(this::showGuide, 800);
    }
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
    root.setBackgroundColor(BG);
    root.setPadding(dp(12), dp(8), dp(12), dp(10));

    // 标题行(右侧 ⋮ 菜单:说明 / 联系人)
    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setOrientation(LinearLayout.HORIZONTAL);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout titleCol = new LinearLayout(this);
    titleCol.setOrientation(LinearLayout.VERTICAL);
    TextView title = new TextView(this);
    title.setText(R.string.app_name);
    title.setTextSize(18);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(TXT);
    TextView subtitle = new TextView(this);
    String ver = "v0.5";
    try { ver = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
    subtitle.setText(getString(R.string.subtitle_fmt, ver));
    subtitle.setTextSize(10.5f);
    subtitle.setTextColor(DIM);
    titleCol.addView(title);
    titleCol.addView(subtitle);
    titleRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView menuBtn = new TextView(this);
    menuBtn.setText("⋮");
    menuBtn.setTextSize(26);
    menuBtn.setTextColor(TXT);
    menuBtn.setGravity(Gravity.CENTER);
    menuBtn.setPadding(dp(14), dp(2), dp(12), dp(4));
    menuBtn.setOnClickListener(this::showMenu);
    titleRow.addView(menuBtn);
    root.addView(titleRow);

    // Tab 栏:演练 / 分诊 / SSH目标
    LinearLayout tabBar = new LinearLayout(this);
    tabBar.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    tabLp.setMargins(dp(3), dp(8), dp(3), dp(4));
    tabs[0] = mkTab(getString(R.string.tab_drill), 0);
    tabs[1] = mkTab(getString(R.string.tab_triage), 1);
    tabs[2] = mkTab(getString(R.string.tab_ssh), 2);
    tabBar.addView(tabs[0], tabLp);
    tabBar.addView(tabs[1], new LinearLayout.LayoutParams(tabLp));
    tabBar.addView(tabs[2], new LinearLayout.LayoutParams(tabLp));
    root.addView(tabBar);

    // Tab 内容区(占上半屏,内部各自可滚动)
    FrameLayout content = new FrameLayout(this);
    tabViews[0] = drillTab();
    tabViews[1] = triageTab();
    tabViews[2] = sshTab();
    content.addView(tabViews[0]);
    content.addView(tabViews[1]);
    content.addView(tabViews[2]);
    root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    // 常驻事件流(单屏可见)
    LinearLayout feedCapRow = new LinearLayout(this);
    feedCapRow.setOrientation(LinearLayout.HORIZONTAL);
    feedCapRow.setGravity(Gravity.CENTER_VERTICAL);
    feedCapRow.setPadding(0, dp(8), 0, dp(4));
    TextView feedCap = new TextView(this);
    feedCap.setText(R.string.feed_caption);
    feedCap.setTextSize(12);
    feedCap.setTypeface(Typeface.DEFAULT_BOLD);
    feedCap.setTextColor(DIM);
    feedCapRow.addView(feedCap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView clearBtn = new TextView(this);
    clearBtn.setText(R.string.feed_clear);
    clearBtn.setTextSize(12);
    clearBtn.setTextColor(PRIMARY);
    clearBtn.setPadding(dp(8), dp(2), dp(4), dp(2));
    clearBtn.setOnClickListener(v -> { feed.clear(); feed.render(); });
    feedCapRow.addView(clearBtn);
    root.addView(feedCapRow);

    feedScroll = new ScrollView(this);
    feedScroll.setBackgroundResource(R.drawable.feed_bg);
    feedView = new TextView(this);
    feedView.setTextSize(11.5f);
    feedView.setTypeface(Typeface.MONOSPACE);
    feedView.setTextColor(TXT);
    feedView.setTextIsSelectable(true);
    feedView.setPadding(dp(10), dp(8), dp(10), dp(8));
    feedScroll.addView(feedView);
    root.addView(feedScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    setContentView(root);
    feed = new EventFeed(feedView, feedScroll, this::targetTagOf);
    setTab(0);
  }

  private TextView mkTab(String s, int idx) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(13);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(10), dp(8), dp(10), dp(8));
    t.setTextColor(DIM);
    t.setOnClickListener(v -> setTab(idx));
    return t;
  }

  private void setTab(int idx) {
    for (int i = 0; i < 3; i++) {
      tabViews[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
      tabs[i].setTextColor(i == idx ? CYAN : DIM);
      tabs[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
      tabs[i].setBackgroundResource(i == idx ? R.drawable.tab_active : 0);
    }
  }

  /** Tab①演练:状态卡 + 演练流程(步骤按钮) */
  private View drillTab() {
    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    LinearLayout statusCard = card();
    statusLine1 = statusLine();
    statusLine2 = statusLine();
    statusLine3 = statusLine();
    statusLine4 = statusLine();
    statusCard.addView(statusLine1);
    statusCard.addView(statusLine2);
    statusCard.addView(statusLine3);
    statusCard.addView(statusLine4);
    root.addView(statusCard);

    LinearLayout steps = card();

    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setVisibility(View.GONE);
    progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
    progressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1D2C44));
    LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
    pLp.setMargins(dp(4), dp(4), dp(4), dp(2));
    steps.addView(progressBar, pLp);

    btnNet = makeBtn(getString(R.string.btn_net), false);
    btnInstall = makeBtn(getString(R.string.btn_install), false);
    btnLoad = makeBtn(getString(R.string.btn_load), false);
    btnTarget = makeBtn(getString(R.string.btn_target), false);
    btnAttack = makeBtn(getString(R.string.btn_attack), false);
    btnDefender = makeBtn(getString(R.string.btn_defender), false);
    btnDemo = makeBtn(getString(R.string.btn_demo), true);
    Button btnStop = makeBtn(getString(R.string.btn_stop), false);
    Button btnClearBan = makeBtn(getString(R.string.btn_clearban), false);

    btnNet.setOnClickListener(v -> bgProgress(btnNet, false, this::doNetProgress));
    btnInstall.setOnClickListener(v -> bgProgress(btnInstall, false, this::doInstallProgress));
    btnLoad.setOnClickListener(v -> bgProgress(btnLoad, true, this::doLoadProgress));
    btnTarget.setOnClickListener(v -> {
      if (sshDrillMode()) bgBusy(btnTarget, this::sshTargetProbe);
      else bgBusy(btnTarget, () -> {
        String err = lab.startTarget();
        if (err != null) onEvent(SecLab.TYPE_INFO, err);
      });
    });
    btnAttack.setOnClickListener(v -> {
      if (sshDrillMode()) { sshBruteConfirm(); return; }
      attackDone = false;
      bg(() -> { lab.startAttack(4); runOnUiThread(this::refreshStatus); });
    });
    btnDefender.setOnClickListener(v -> {
      if (sshDrillMode()) bgBusy(btnDefender, this::sshDefenseStatus);
      else bgBusy(btnDefender, lab::startDefender);
    });
    btnDemo.setOnClickListener(v -> {
      if (sshDrillMode()) sshDemoConfirm();
      else bgBusy(btnDemo, this::doDemo);
    });
    btnStop.setOnClickListener(v -> bgBusy(btnStop, this::doStopAll));
    btnClearBan.setOnClickListener(v -> bg(() -> { lab.clearBans(); runOnUiThread(this::refreshStatus); }));

    steps.addView(buttonRow(btnNet, btnInstall));
    steps.addView(buttonRow(btnLoad, btnTarget));
    steps.addView(buttonRow(btnAttack, btnDefender));
    LinearLayout lastRow = new LinearLayout(this);
    lastRow.setOrientation(LinearLayout.HORIZONTAL);
    lastRow.addView(btnDemo, btnLp());
    lastRow.addView(btnStop, btnLp());
    lastRow.addView(btnClearBan, btnLp());
    steps.addView(lastRow);
    root.addView(steps);

    sv.addView(root);
    return sv;
  }

  /** Tab②分诊:手动分诊(按钮竖排;输入框自适应内容高度) */
  private View triageTab() {
    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    LinearLayout manualCard = card();
    manualInput = makeInput("粘贴一段安全相关文本手动分诊…");
    manualInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    manualInput.setMinLines(3);
    manualInput.setMaxLines(12);
    manualInput.setGravity(Gravity.TOP | Gravity.START);
    manualInput.setVerticalScrollBarEnabled(true);
    Button sampleBtn = makeBtn(getString(R.string.btn_sample), false);
    sampleBtn.setOnClickListener(v -> showSamples());
    Button mtBtn = makeBtn(getString(R.string.btn_triage), true);
    mtBtn.setOnClickListener(v -> bg(() -> {
      String t = manualInput.getText().toString().trim();
      if (t.isEmpty()) return;
      onEvent(SecLab.TYPE_TRIAGE, "手动分诊: " + lab.classifyText(t));
    }));
    manualCard.addView(manualInput, fullLp());
    manualCard.addView(sampleBtn, fullLp());
    manualCard.addView(mtBtn, fullLp());
    root.addView(manualCard);

    sv.addView(root);
    return sv;
  }

  /** Tab③SSH目标机防护演习(仅限自有设备;按钮按序号门控,逐步解锁) */
  private View sshTab() {
    ScrollView sv = new ScrollView(this);
    sv.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    LinearLayout box = card();

    LinearLayout row1 = new LinearLayout(this);
    row1.setOrientation(LinearLayout.HORIZONTAL);
    sshHost = makeInput(getString(R.string.ssh_host_hint));
    sshHost.setText(prefs.getString("ssh_host", ""));
    sshPort = makeInput(getString(R.string.ssh_port));
    sshPort.setInputType(InputType.TYPE_CLASS_NUMBER);
    sshPort.setText(prefs.getString("ssh_port", "22"));
    row1.addView(sshHost, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
    row1.addView(sshPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    box.addView(row1);

    LinearLayout row2 = new LinearLayout(this);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    sshUser = makeInput(getString(R.string.ssh_user));
    sshUser.setText(prefs.getString("ssh_user", ""));
    sshPass = makeInput(getString(R.string.ssh_pass));
    sshPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    row2.addView(sshUser, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    row2.addView(sshPass, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    box.addView(row2);

    sshTargetLine = new TextView(this);
    sshTargetLine.setTextSize(11);
    sshTargetLine.setTextColor(DIM);
    sshTargetLine.setPadding(dp(4), dp(4), dp(4), dp(2));
    box.addView(sshTargetLine);
    TextView setTargetBtn = new TextView(this);
    setTargetBtn.setText(R.string.ssh_set_target);
    setTargetBtn.setTextSize(12);
    setTargetBtn.setTextColor(PRIMARY);
    setTargetBtn.setPadding(dp(4), dp(2), dp(4), dp(6));
    setTargetBtn.setOnClickListener(v -> saveTargetConfig());
    box.addView(setTargetBtn);

    btnSshTest = makeBtn(getString(R.string.ssh_test), false);
    btnSshCheck = makeBtn(getString(R.string.ssh_check), false);
    btnSshDeploy = makeBtn(getString(R.string.ssh_deploy), false);
    btnSshAlerts = makeBtn(getString(R.string.ssh_alerts), false);
    btnSshBan = makeBtn(getString(R.string.ssh_ban), false);
    btnSshUnban = makeBtn(getString(R.string.ssh_unban), false);
    btnSshTest.setOnClickListener(v -> bgBusy(btnSshTest, this::sshTest));
    btnSshCheck.setOnClickListener(v -> bgBusy(btnSshCheck, this::sshCheck));
    btnSshDeploy.setOnClickListener(v -> sshDeployConfirm(btnSshDeploy));
    btnSshAlerts.setOnClickListener(v -> bgBusy(btnSshAlerts, this::sshAlerts));
    btnSshBan.setOnClickListener(v -> sshBanDialog(true));
    btnSshUnban.setOnClickListener(v -> sshBanDialog(false));
    box.addView(buttonRow(btnSshTest, btnSshCheck));
    box.addView(buttonRow(btnSshDeploy, btnSshAlerts));
    box.addView(buttonRow(btnSshBan, btnSshUnban));
    root.addView(box);

    TextView note = new TextView(this);
    note.setText(R.string.ssh_note);
    note.setTextSize(11);
    note.setTextColor(DIM);
    note.setPadding(dp(4), dp(8), dp(4), 0);
    root.addView(note);

    loadTargetConfig();
    sv.addView(root);
    return sv;
  }

  // ================= 演练目标机配置(TargetStore: /sdcard/sec-lab/ssh-target.json) =================

  private int parsePort() {
    int port = 22;
    try { port = Integer.parseInt(sshPort.getText().toString().trim()); } catch (Exception ignored) {}
    if (port < 1 || port > 65535) port = 22;
    return port;
  }

  private void loadTargetConfig() {
    String[] t = TargetStore.load();
    if (t != null) {
      sshHost.setText(t[0]);
      sshPort.setText(t[1]);
      if (!t[2].isEmpty()) sshUser.setText(t[2]);
    }
    updateTargetLine();
  }

  private void saveTargetConfig() { saveTargetConfig(false); }

  /** quiet=true 时不再单独播报(由调用方合并提示) */
  private void saveTargetConfig(boolean quiet) {
    String host = sshHost.getText().toString().trim();
    String user = sshUser.getText().toString().trim();
    if (host.isEmpty() || user.isEmpty()) { onEvent(TYPE_SSH, getString(R.string.need_host_user_set)); return; }
    int port = parsePort();
    try {
      TargetStore.save(host, port, user);
      updateTargetLine();
      if (!quiet) onEvent(TYPE_SSH, getString(R.string.ssh_target_set_fmt, user + "@" + host + ":" + port, TargetStore.FILE));
    } catch (Throwable t) {
      onEvent(TYPE_SSH, getString(R.string.ssh_target_save_fail_fmt, t.getMessage()));
    }
  }

  private void updateTargetLine() {
    if (sshTargetLine == null) return;
    String host = sshHost.getText().toString().trim();
    String user = sshUser.getText().toString().trim();
    if (host.isEmpty()) {
      sshTargetLine.setText(getString(R.string.ssh_target_line_none_fmt, getString(R.string.ssh_set_target)));
    } else {
      sshTargetLine.setText(getString(R.string.ssh_target_line_fmt, user + "@" + host + ":" + parsePort()));
    }
    if (statusLine4 != null) {
      SpannableStringBuilder s = new SpannableStringBuilder();
      seg(s, getString(R.string.status_ssh_line) + " ", DIM);
      if (host.isEmpty()) seg(s, getString(R.string.status_not_set), DIM);
      else seg(s, user + "@" + host + ":" + parsePort(), CYAN);
      seg(s, "   ·   " + getString(R.string.status_remote_note), DIM);
      statusLine4.setText(s);
    }
  }

  private LinearLayout card() {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setBackgroundResource(R.drawable.card);
    c.setPadding(dp(12), dp(10), dp(12), dp(10));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(0, dp(2), 0, dp(4));
    c.setLayoutParams(lp);
    return c;
  }

  private TextView statusLine() {
    TextView t = new TextView(this);
    t.setTextSize(12);
    t.setTextColor(TXT);
    t.setPadding(0, dp(1), 0, dp(1));
    return t;
  }

  private Button makeBtn(String text, boolean primary) {
    Button b = new Button(this);
    b.setText(text);
    b.setTag(text);
    b.setAllCaps(false);
    b.setTextSize(13.5f);
    b.setTextColor(primary ? 0xFFFFFFFF : TXT);
    b.setBackgroundResource(primary ? R.drawable.btn_primary_bg : R.drawable.btn_bg);
    b.setPadding(dp(4), dp(6), dp(4), dp(6));
    b.setMinHeight(0);
    b.setMinimumHeight(dp(40));
    b.setStateListAnimator(null);
    return b;
  }

  private LinearLayout.LayoutParams btnLp() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    return lp;
  }

  private LinearLayout.LayoutParams fullLp() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    return lp;
  }

  private LinearLayout buttonRow(Button a, Button b) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(a, btnLp());
    row.addView(b, btnLp());
    return row;
  }

  private EditText makeInput(String hint) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setHintTextColor(OFF);
    e.setTextColor(TXT);
    e.setTextSize(13);
    e.setBackgroundResource(R.drawable.input_bg);
    e.setPadding(dp(10), dp(8), dp(10), dp(8));
    return e;
  }

  private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

  // ================= 菜单 / 说明 / 联系人 =================

  private void showMenu(View anchor) {
    PopupMenu pm = new PopupMenu(this, anchor);
    pm.getMenu().add(0, 1, 0, R.string.menu_guide);
    pm.getMenu().add(0, 2, 1, R.string.menu_contact);
    pm.getMenu().add(0, 3, 2, R.string.menu_lang);
    pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
      @Override public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == 1) { showGuide(); return true; }
        if (item.getItemId() == 2) { showContact(); return true; }
        if (item.getItemId() == 3) { showLangDialog(); return true; }
        return false;
      }
    });
    pm.show();
  }

  /** 语言切换:跟随系统 / 中文 / English(保存后重建界面) */
  private void showLangDialog() {
    final String[] values = {"", "zh", "en"};
    String cur = Lang.current(this);
    int checked = 0;
    for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) checked = i;
    CharSequence[] opts = {
        getString(R.string.lang_system), getString(R.string.lang_zh), getString(R.string.lang_en)
    };
    new AlertDialog.Builder(this)
        .setTitle(R.string.lang_title)
        .setSingleChoiceItems(opts, checked, (d, w) -> {
          d.dismiss();
          if (!values[w].equals(Lang.current(MainActivity.this))) Lang.set(MainActivity.this, values[w]);
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showGuide() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.guide_title)
        .setMessage(R.string.guide_body)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void showContact() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    v.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView note = new TextView(this);
    note.setText(R.string.contact_note);
    note.setTextSize(12);
    note.setTextColor(DIM);
    note.setGravity(Gravity.CENTER);
    note.setPadding(0, 0, 0, dp(8));
    v.addView(note);
    ImageView iv = new ImageView(this);
    iv.setImageResource(R.drawable.contact_qr);
    iv.setAdjustViewBounds(true);
    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
    iv.setMaxHeight(dp(380));
    v.addView(iv);
    new AlertDialog.Builder(this)
        .setTitle(R.string.contact_title)
        .setView(v)
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void showSamples() {
    List<Pair<String, String>> samples = SecTriage.Companion.getSAMPLES();
    CharSequence[] labels = new CharSequence[samples.size()];
    for (int i = 0; i < samples.size(); i++) labels[i] = samples.get(i).getFirst();
    new AlertDialog.Builder(this)
        .setTitle(R.string.guide_sample_title)
        .setItems(labels, (d, w) -> {
          Pair<String, String> s = samples.get(w);
          manualInput.setText(s.getSecond());
          onEvent(SecLab.TYPE_TRIAGE, "已载入示例[" + s.getFirst() + "],点「" + getString(R.string.btn_triage) + "」查看 NPU 分诊");
        })
        .show();
  }

  // ================= SSH 步骤动作(门控解锁) =================

  private SshOps.Cfg sshCfg() {
    String host = sshHost.getText().toString().trim();
    String user = sshUser.getText().toString().trim();
    String pass = sshPass.getText().toString();
    if (host.isEmpty() || user.isEmpty()) { onEvent(TYPE_SSH, getString(R.string.need_host_user)); return null; }
    if (pass.isEmpty()) { onEvent(TYPE_SSH, getString(R.string.need_pass)); return null; }
    int port = parsePort();
    prefs.edit().putString("ssh_host", host).putString("ssh_port", String.valueOf(port)).putString("ssh_user", user).apply();
    updateTargetLine();
    SshOps.Cfg c = new SshOps.Cfg();
    c.host = host; c.port = port; c.user = user; c.pass = pass;
    return c;
  }

  private void sshTest() {
    SshOps.Cfg c = sshCfg(); if (c == null) return;
    if (ssh.test(c)) {
      sshTestOk = true;
      saveTargetConfig(true);
      onEvent(TYPE_SSH, getString(R.string.ssh_connected_fmt, c.user + "@" + c.host + ":" + c.port));
    }
  }

  private void sshCheck() {
    SshOps.Cfg c = sshCfg(); if (c == null) return;
    sshCheckOk = ssh.checkDefense(c);
    if (sshCheckOk) onEvent(TYPE_SSH, getString(R.string.ssh_check_done));
  }

  private void sshDeployConfirm(Button b) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.ssh_deploy)
        .setMessage(R.string.ssh_deploy_confirm)
        .setPositiveButton(R.string.ssh_exec, (d, w) -> {
          SshOps.Cfg c = sshCfg();
          if (c != null) bgBusy(b, () -> {
            if (ssh.deployLab(c)) {
              sshDeployOk = true;
              onEvent(TYPE_SSH, getString(R.string.ssh_deploy_done));
            }
          });
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void sshAlerts() {
    SshOps.Cfg c = sshCfg(); if (c == null) return;
    if (!ssh.fetchAlerts(c)) return;
    sshAlertsOk = true;
    onEvent(TYPE_SSH, getString(R.string.ssh_alerts_done));
    int n = ssh.lastCount();
    String top = ssh.lastTopIp();
    if (!top.isEmpty()) lastTopIp = top;
    StringBuilder sb = new StringBuilder();
    sb.append("SSH 日志告警:目标机 ").append(c.host).append(" 近期失败登录/无效用户 ").append(n).append(" 条");
    if (!top.isEmpty()) sb.append(",来源 TOP ").append(top).append(" 高频尝试");
    String sample = ssh.lastSample();
    if (!sample.isEmpty()) sb.append(";样例: ").append(sample);
    sb.append("。来源为自有靶机日志,疑似 SSH 弱口令爆破/口令喷洒探测。");
    if (!triage.isLoaded()) {
      onEvent(SecLab.TYPE_TRIAGE, getString(R.string.ssh_triage_no_engine_fmt,
          getString(R.string.tab_drill), getString(R.string.btn_load)));
      return;
    }
    onEvent(SecLab.TYPE_TRIAGE, "SSH 分诊[NPU]: " + lab.classifyText(sb.toString()));
    if (!top.isEmpty()) onEvent(TYPE_SSH, getString(R.string.ssh_ban_hint_fmt, getString(R.string.ssh_ban), top));
    else onEvent(TYPE_SSH, getString(R.string.ssh_no_ip));
  }

  private void sshBanDialog(boolean ban) {
    EditText et = makeInput(getString(R.string.ssh_ip_hint));
    et.setText(lastTopIp);
    new AlertDialog.Builder(this)
        .setTitle(ban ? R.string.ssh_ban : R.string.ssh_unban)
        .setView(et)
        .setPositiveButton(R.string.ssh_exec, (d, w) -> {
          String ip = et.getText().toString().trim();
          if (!SshOps.validIp(ip)) { onEvent(TYPE_SSH, getString(R.string.ssh_bad_ip_fmt, ip)); return; }
          SshOps.Cfg c = sshCfg(); if (c == null) return;
          bg(() -> {
            boolean ok = ban ? ssh.ban(c, ip) : ssh.unban(c, ip);
            if (ok) {
              if (ban) { sshBanOk = true; onEvent(TYPE_SSH, getString(R.string.ssh_banned_fmt, ip)); }
              else { sshUnbanOk = true; onEvent(TYPE_SSH, getString(R.string.ssh_unbanned_fmt, ip)); }
            }
            runOnUiThread(this::refreshStatus);
          });
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  // ================= 事件流 =================

  @Override
  public void onEvent(String type, String text) {
    runOnUiThread(() -> {
      if (feed == null) return;
      if (text.startsWith("爆破结束")) attackDone = true;
      feed.add(type, text);
      feed.render();
      refreshStatus();
    });
  }

  /** 事件行右侧目标机标注:拦截/封禁等日志一眼可辨作用对象 */
  private String targetTagOf(String type, String text) {
    if (TYPE_SSH.equals(type)) return sshTargetTag();
    if (SecLab.TYPE_TRIAGE.equals(type) && text != null && text.startsWith("SSH 分诊")) return sshTargetTag();
    switch (type) {
      case SecLab.TYPE_ATTACK:
      case SecLab.TYPE_DEFENSE:
      case SecLab.TYPE_TARGET:
        return getString(R.string.tag_local_fmt, lab == null ? 18080 : lab.port());
      default:
        return "";
    }
  }

  private String sshTargetTag() {
    String host = sshHost == null ? "" : sshHost.getText().toString().trim();
    if (host.isEmpty()) return getString(R.string.tag_ssh_none);
    String user = sshUser.getText().toString().trim();
    return getString(R.string.tag_ssh_fmt, user + "@" + host + ":" + parsePort());
  }

  private void refreshStatus() {
    if (statusLine1 == null) return;

    boolean srcReady = triage.sourceReady();
    boolean modelReady = triage.modelReady();
    SpannableStringBuilder s1 = new SpannableStringBuilder();
    seg(s1, getString(R.string.status_model) + " ", DIM);
    seg(s1, modelReady
        ? getString(srcReady ? R.string.status_ready_src : R.string.status_ready_installed)
        : getString(R.string.status_missing), modelReady ? GREEN : AMBER);
    seg(s1, "   ·   " + getString(R.string.status_engine) + " ", DIM);
    seg(s1, triage.isLoaded() ? triage.backend() : getString(R.string.engine_unknown), triage.isLoaded() ? CYAN : DIM);
    statusLine1.setText(s1);

    SpannableStringBuilder s2 = new SpannableStringBuilder();
    boolean sshMode = sshDrillMode();
    seg(s2, getString(R.string.status_target) + " ", DIM);
    if (sshMode) {
      seg(s2, getString(R.string.status_target_ssh_fmt,
          sshUser.getText().toString().trim() + "@" + sshHost.getText().toString().trim() + ":" + parsePort()), AMBER);
    } else if (lab.targetRunning()) {
      seg(s2, getString(R.string.status_running_local_fmt, lab.port()), GREEN);
    } else {
      seg(s2, getString(R.string.status_stopped_local), DIM);
    }
    seg(s2, "   ·   " + getString(R.string.status_defense) + " ", DIM);
    if (sshMode) {
      seg(s2, getString(R.string.status_remote_f2b), GREEN);
    } else if (lab.defenderRunning()) {
      seg(s2, getString(R.string.status_running_window_fmt, lab.windowSec(), lab.threshold()), GREEN);
    } else {
      seg(s2, getString(R.string.status_stopped_local), DIM);
    }
    statusLine2.setText(s2);

    List<String> bans = lab.bannedIps();
    SpannableStringBuilder s3 = new SpannableStringBuilder();
    seg(s3, getString(R.string.status_bans) + " ", DIM);
    seg(s3, bans.isEmpty() ? getString(R.string.status_none) : String.join(", ", bans), bans.isEmpty() ? DIM : RED);
    statusLine3.setText(s3);
    updateTargetLine();

    setStepDone(btnNet, srcReady);
    setStepDone(btnInstall, triage.workDirReady());
    setStepDone(btnLoad, triage.isLoaded());
    setStepDone(btnTarget, lab.targetRunning());
    setStepDone(btnDefender, lab.defenderRunning());
    if (lab.attacking()) {
      btnAttack.setText("⏳ " + btnAttack.getTag() + " " + lab.attackProgress() + "/" + lab.attackTotal());
      btnAttack.setTextColor(TXT);
    } else {
      setStepDone(btnAttack, attackDone);
    }

    gate(btnSshTest, true, sshTestOk);
    gate(btnSshCheck, sshTestOk, sshCheckOk);
    gate(btnSshDeploy, sshCheckOk, sshDeployOk);
    gate(btnSshAlerts, sshCheckOk, sshAlertsOk);
    gate(btnSshBan, sshAlertsOk, sshBanOk);
    gate(btnSshUnban, sshBanOk, sshUnbanOk);
  }

  private void seg(SpannableStringBuilder sb, String text, int color) {
    int start = sb.length();
    sb.append(text);
    sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private void setStepDone(Button b, boolean done) {
    if (b == null) return;
    b.setText((done ? "✓ " : "") + b.getTag());
    b.setTextColor(done ? GREEN : TXT);
  }

  /** SSH 门控:enabled=false 时置灰不可点;done=true 时打勾变绿 */
  private void gate(Button b, boolean enabled, boolean done) {
    if (b == null) return;
    b.setEnabled(enabled);
    b.setText((done ? "✓ " : "") + b.getTag());
    b.setTextColor(!enabled ? OFF : (done ? GREEN : TXT));
  }

  // ================= 后台动作 =================

  private interface Cmd { void run() throws Exception; }

  private void run(Cmd c) {
    new Thread(() -> {
      try { c.run(); }
      catch (Throwable t) { onEvent(SecLab.TYPE_INFO, "错误: " + t.getMessage()); }
    }, "sec-ui-bg").start();
  }

  private void bg(Cmd c) { run(c); }

  /** 后台执行并把按钮置为「处理中」,完成后恢复并刷新步骤状态 */
  private void bgBusy(Button b, Cmd c) {
    Object tag = b.getTag();
    b.setEnabled(false);
    b.setText("… " + tag);
    b.setTextColor(DIM);
    run(() -> {
      try { c.run(); }
      finally {
        runOnUiThread(() -> {
          b.setEnabled(true);
          b.setText(String.valueOf(tag));
          b.setTextColor(b == btnDemo ? 0xFFFFFFFF : TXT);
          refreshStatus();
        });
      }
    });
  }

  private interface PCmd { void run(IntConsumer p) throws Exception; }

  /** 后台执行:进度条可见(确定/不确定),按钮文字带百分比,完成后恢复并刷新步骤状态 */
  private void bgProgress(Button b, boolean indeterminate, PCmd c) {
    Object tag = b.getTag();
    progressBar.setVisibility(View.VISIBLE);
    progressBar.setIndeterminate(indeterminate);
    if (!indeterminate) progressBar.setProgress(0);
    b.setEnabled(false);
    b.setText("… " + tag);
    b.setTextColor(DIM);
    run(() -> {
      try {
        c.run(p -> runOnUiThread(() -> {
          if (!indeterminate) progressBar.setProgress(p);
          b.setText("… " + tag + (indeterminate ? "" : " " + p + "%"));
        }));
      } finally {
        runOnUiThread(() -> {
          b.setEnabled(true);
          b.setText(String.valueOf(tag));
          b.setTextColor(TXT);
          progressBar.setVisibility(View.GONE);
          progressBar.setIndeterminate(false);
          refreshStatus();
        });
      }
    });
  }

  private void doNetProgress(IntConsumer p) {
    onEvent(SecLab.TYPE_INFO, "从 ModelScope 公共仓库(supermy/myjev → " + ModelNet.DEFAULT_DIR + ")拉取端侧模型…");
    onEvent(SecLab.TYPE_CMD, "$ wget -c \"https://modelscope.cn/api/v1/models/supermy/myjev/repo?Revision=master&FilePath="
        + ModelNet.DEFAULT_DIR + "/<file>\"   # 等效:应用内置下载(自动跳过已完整文件,支持断点续传)");
    File dir = triage.getSourceDir();
    boolean mtk = triage.mtkNpuAvailable();
    String err = ModelNet.download(dir, ModelNet.DEFAULT_DIR, mtk, new ModelNet.Log() {
      @Override public void line(String t) { onEvent(SecLab.TYPE_INFO, t); }
      @Override public void percent(int v) { p.accept(v); }
    });
    onEvent(SecLab.TYPE_INFO, err == null ? "网络拉取完成(" + (mtk ? "NPU" : "GPU") + " 变体)" : err);
  }

  private void doInstallProgress(IntConsumer p) {
    if (!triage.sourceReady()) {
      onEvent(SecLab.TYPE_INFO, "源包缺失: " + SecTriage.SOURCE_DIR + ",可点「" + getString(R.string.btn_net) + "」从网络拉取");
      return;
    }
    onEvent(SecLab.TYPE_INFO, "正在安装模型(拷入应用私有目录,约 650MB,稍候)…");
    String err = triage.installWithProgress(p);
    onEvent(SecLab.TYPE_INFO, err == null ? "模型安装完成" : err);
  }

  private void doLoadProgress(IntConsumer p) {
    if (!triage.modelReady()) {
      onEvent(SecLab.TYPE_INFO, "模型未就绪,请先「" + getString(R.string.btn_net) + "」或「" + getString(R.string.btn_install) + "」");
      return;
    }
    onEvent(SecLab.TYPE_INFO, "正在加载引擎(" + (triage.mtkNpuAvailable() ? "NPU" : "GPU/CPU") + ")…");
    String err = triage.load();
    onEvent(SecLab.TYPE_INFO, err == null ? "引擎就绪: " + triage.backend() : err);
  }

  private void doDemo() {
    if (!triage.modelReady()) {
      onEvent(SecLab.TYPE_INFO, "提示: 模型未就绪,将仅演示攻防流程(无 NPU 分诊),可先点「" + getString(R.string.btn_net) + "」");
    } else if (!triage.isLoaded()) {
      onEvent(SecLab.TYPE_INFO, "正在加载引擎…");
      String err = triage.load();
      onEvent(SecLab.TYPE_INFO, err == null ? "引擎就绪: " + triage.backend() : err);
    }
    attackDone = false;
    lab.runDemo(4);
    runOnUiThread(this::refreshStatus);
  }

  // ================= SSH 靶标模式(演练页 ④⑤⑥;仅限自有/已授权设备) =================

  /** 已填写主机/用户即进入 SSH 靶标模式:④⑤⑥ 与一键演示改为指向目标机 */
  private boolean sshDrillMode() {
    return sshHost != null
        && !sshHost.getText().toString().trim().isEmpty()
        && !sshUser.getText().toString().trim().isEmpty();
  }

  /** 靶标模式配置(口令尝试/探测不需要密码;会话类操作另行校验) */
  private SshOps.Cfg sshDrillCfg() {
    SshOps.Cfg c = new SshOps.Cfg();
    c.host = sshHost.getText().toString().trim();
    c.port = parsePort();
    c.user = sshUser.getText().toString().trim();
    c.pass = sshPass.getText().toString();
    return c;
  }

  /** ④ 靶标检查:探测目标机 sshd 端口 banner(无需登录) */
  private void sshTargetProbe() {
    String host = sshHost.getText().toString().trim();
    int port = parsePort();
    onEvent(TYPE_SSH, "SSH 靶标检查:探测 " + host + ":" + port + " 的 sshd(无需登录)…");
    String banner = ssh.probeBanner(host, port);
    if (banner == null) onEvent(TYPE_SSH, "目标机 " + host + ":" + port + " 端口不可达(sshd 未运行或网络不通)");
    else onEvent(TYPE_SSH, "靶标就绪:收到标识 " + banner + "(演练靶标 = 该机 sshd)");
  }

  /** ⑤ 口令尝试:每次操作都必须确认授权 */
  private void sshBruteConfirm() {
    String target = sshUser.getText().toString().trim() + "@" + sshHost.getText().toString().trim() + ":" + parsePort();
    new AlertDialog.Builder(this)
        .setTitle(getString(R.string.btn_attack) + "(SSH 靶标)")
        .setMessage(getString(R.string.ssh_brute_confirm, target))
        .setPositiveButton(R.string.ssh_exec, (d, w) -> bgBusy(btnAttack, this::sshBruteRun))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void sshBruteRun() {
    attackDone = false;
    SshOps.Cfg c = sshDrillCfg();
    ssh.resetBrute();
    ssh.bruteAttempts(c, SshOps.DEFAULT_BRUTE_USERS, SshOps.DEFAULT_BRUTE_PWDS, 20);
    onEvent(SecLab.TYPE_DEFENSE, "远端防御:失败次数超过目标机 fail2ban maxretry 后,本机出口 IP 将被封禁;"
        + "点⑥可查询封禁状态(连接被拒/超时即防御生效,可能自锁 SSH)");
    runOnUiThread(this::refreshStatus);
  }

  /** ⑥ 防御:查询目标机 fail2ban 状态与封禁列表(被封禁后新会话失败属预期) */
  private void sshDefenseStatus() {
    if (sshPass.getText().toString().isEmpty()) { onEvent(TYPE_SSH, "查询远端防御状态需要 SSH 会话:请在「" + getString(R.string.tab_ssh) + "」页填写密码"); return; }
    SshOps.Cfg c = sshCfg(); if (c == null) return;
    boolean ok = ssh.defenseStatus(c);
    if (!ok) onEvent(TYPE_SSH, "无法建立新 SSH 会话——若刚做过口令尝试,本机出口 IP 可能已被目标机 fail2ban 封禁"
        + "(防御生效的典型自锁后果);可等 bantime 结束,或在目标机控制台执行 fail2ban-client set sshd unbanip <本机IP> 解封");
  }

  /** 一键演示(SSH 靶标模式):先弹授权确认再连跑 ④→⑤→⑥→④(拉取告警分诊) */
  private void sshDemoConfirm() {
    String target = sshUser.getText().toString().trim() + "@" + sshHost.getText().toString().trim() + ":" + parsePort();
    new AlertDialog.Builder(this)
        .setTitle(R.string.btn_demo)
        .setMessage(getString(R.string.demo_ssh_confirm, target))
        .setPositiveButton(R.string.ssh_exec, (d, w) -> bgBusy(btnDemo, this::doDemoSsh))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void doDemoSsh() {
    if (!triage.modelReady()) {
      onEvent(SecLab.TYPE_INFO, "提示: 模型未就绪,将演示流程但无 NPU 分诊,可先点「" + getString(R.string.btn_net) + "」");
    } else if (!triage.isLoaded()) {
      onEvent(SecLab.TYPE_INFO, "正在加载引擎…");
      String err = triage.load();
      onEvent(SecLab.TYPE_INFO, err == null ? "引擎就绪: " + triage.backend() : err);
    }
    onEvent(SecLab.TYPE_INFO, "一键演示(SSH 靶标模式):④靶标检查 → ⑤口令尝试 → ⑥远端防御状态 → ④拉取告警并 NPU 分诊");
    sshTargetProbe();
    sshBruteRun();
    sshDefenseStatus();
    if (!sshPass.getText().toString().isEmpty()) sshAlerts();
    onEvent(SecLab.TYPE_DEFENSE, "SSH 靶标演示完成:攻击(口令尝试)真实计入目标机日志与 fail2ban,防御(自动封禁)在目标机上执行;"
        + "可在事件流/「④拉取告警」查看封禁名单");
    runOnUiThread(this::refreshStatus);
  }

  private void doStopAll() {
    lab.stopAttack();
    lab.stopDefender();
    lab.stopTarget();
    ssh.stopBrute();
    runOnUiThread(this::refreshStatus);
  }

  private String formatTriage(JSONObject r) {
    StringBuilder sb = new StringBuilder();
    sb.append("类别=").append(r.optString("category", "?"))
      .append("(").append(String.format(Locale.US, "%.2f", r.optDouble("categoryConf", 0))).append(")");
    sb.append(" 严重度=").append(String.format(Locale.US, "%.2f", r.optDouble("severity", 0))).append("/5");
    String lab2 = r.optString("severityLabel", "");
    if (!lab2.isEmpty()) sb.append("(").append(lab2).append(")");
    sb.append(" 人工研判=").append(String.format(Locale.US, "%.2f", r.optDouble("review", 0)));
    sb.append(" → ").append(suggest(r));
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
