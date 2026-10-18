package com.selfhost.layasec;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 事件流:事件存储(记录时定格时间戳)+ 按类型着色渲染 + 行尾目标机标注 + 贴底自动滚动。
 * 仅限 UI 线程调用(时间戳格式器非线程安全)。
 */
class EventFeed {

  /** 行尾灰色目标机标注提供者(如 @本机靶标 127.0.0.1:18080);无需标注返回空串 */
  interface TagProvider {
    String tagOf(String type, String text);
  }

  private static final int MAX_LINES = 800;
  private static final int TRIM_BATCH = 200;

  private static class Ev {
    final String type, text, time;
    Ev(String type, String text, String time) { this.type = type; this.text = text; this.time = time; }
  }

  private final List<Ev> events = new ArrayList<>();
  private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
  private final TextView view;
  private final ScrollView scroll;
  private final TagProvider tags;
  private boolean scrollPending = false;

  EventFeed(TextView view, ScrollView scroll, TagProvider tags) {
    this.view = view;
    this.scroll = scroll;
    this.tags = tags;
  }

  /** 记录一条事件(时间戳在记录时定格,历史行时间不再随渲染变化) */
  void add(String type, String text) {
    events.add(new Ev(type, text, tf.format(new Date())));
    if (events.size() > MAX_LINES) events.subList(0, TRIM_BATCH).clear();
  }

  void clear() { events.clear(); }

  void render() {
    boolean stick = atBottom();
    SpannableStringBuilder sb = new SpannableStringBuilder();
    for (Ev e : events) {
      int start = sb.length();
      sb.append("[").append(e.time).append("] ").append(e.text);
      sb.setSpan(new ForegroundColorSpan(colorOf(e.type)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      String tag = tags == null ? "" : tags.tagOf(e.type, e.text);
      if (tag != null && !tag.isEmpty()) {
        int ts = sb.length();
        sb.append("      @").append(tag);
        sb.setSpan(new ForegroundColorSpan(Theme.DIM), ts, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      sb.append("\n");
    }
    view.setText(sb);
    if (stick) scrollToLatest();
  }

  /**
   * 贴底滚动:setText 后布局在下一帧才完成,单次 post 会滚到「旧内容底部」,
   * 高频事件下永远慢一拍;二次 post 确保新内容布局完成后再滚到最新行。
   * scrollPending 合并连续事件,避免同一帧排队多次滚动。
   */
  private void scrollToLatest() {
    if (scrollPending) return;
    scrollPending = true;
    scroll.post(() -> scroll.post(() -> {
      scrollPending = false;
      scroll.fullScroll(View.FOCUS_DOWN);
    }));
  }

  /** 事件类型 → 颜色(与学习用配色一致) */
  static int colorOf(String type) {
    if (SecLab.TYPE_SSH.equals(type)) return Theme.AMBER;
    switch (type == null ? "" : type) {
      case SecLab.TYPE_TARGET: return 0xFF60A5FA;
      case SecLab.TYPE_ATTACK: return Theme.RED;
      case SecLab.TYPE_DEFENSE: return Theme.GREEN;
      case SecLab.TYPE_TRIAGE: return 0xFFC084FC;
      case SecLab.TYPE_CMD: return Theme.CYAN;
      default: return 0xFF9AA7BD;
    }
  }

  private boolean atBottom() {
    if (view.getMeasuredHeight() == 0) return true;
    int slack = Math.round(view.getResources().getDisplayMetrics().density * 48);
    return scroll.getScrollY() + scroll.getHeight() >= view.getMeasuredHeight() - slack;
  }
}
