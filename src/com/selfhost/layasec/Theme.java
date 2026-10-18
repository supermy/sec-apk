package com.selfhost.layasec;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/** 全局深色主题配色 + 富文本小工具(供 MainActivity/EventFeed 共用)。 */
final class Theme {

  static final int BG = 0xFF0B1220;
  static final int TXT = 0xFFE6EDF7;
  static final int DIM = 0xFF8FA3BF;
  static final int OFF = 0xFF5A6E8C;
  static final int PRIMARY = 0xFF4C8DFF;
  static final int CYAN = 0xFF22D3EE;
  static final int GREEN = 0xFF34D399;
  static final int RED = 0xFFF87171;
  static final int AMBER = 0xFFF59E0B;

  private Theme() {}

  /** 向 SpannableStringBuilder 追加一段指定颜色的文本 */
  static void seg(SpannableStringBuilder sb, String text, int color) {
    int start = sb.length();
    sb.append(text);
    sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}
