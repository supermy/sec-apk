package com.selfhost.layasec;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * 应用内语言:"" = 跟随系统,"zh","en"。
 * 统一走「保存偏好 + createConfigurationContext 覆写」,各版本行为一致、可离线验证;
 * 将来 targetSdk 升到 33+ 时再同步系统 per-app locale(LocaleManager + localeConfig)。
 */
public final class Lang {
  static final String PREFS = "sec-lab";
  static final String KEY = "lang";

  private Lang() {}

  public static String current(Context c) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
  }

  /** attachBaseContext 用:按保存值覆写资源上下文 */
  public static Context wrap(Context base) {
    String lang = current(base);
    if (lang.isEmpty()) return base;
    Configuration cfg = new Configuration(base.getResources().getConfiguration());
    cfg.setLocale(new Locale(lang));
    return base.createConfigurationContext(cfg);
  }

  /** 设置语言并重建界面("" 恢复跟随系统) */
  public static void set(Activity a, String lang) {
    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply();
    a.recreate();
  }
}
