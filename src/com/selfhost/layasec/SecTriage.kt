package com.selfhost.layasec

import android.content.Context
import android.util.Log
import com.laya.LayaRunnerEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 网络攻防告警分诊:把一段安全相关文本(state)交给端侧 Laya 决策模型(laya-litert-sec),
 * 单次前向返回三问:类别(正常业务/侦察探测/攻击威胁/钓鱼欺诈/防御处置)、严重度 1-5、
 * 是否需要人工研判(noul)。模型在 MTK 天玑上走 NPU(MDLA dispatch,独立进程 runner),
 * 非 MTK 无 NPU 时尝试 GPU(wfp16 主图,sec 包通常只带 NPU 主图)。
 */
class SecTriage(private val ctx: Context) {

  companion object {
    private const val TAG = "SecTriage"
    const val SOURCE_DIR = "/sdcard/models/laya-litert-sec/phone"
    /** runner 必需文件(任一缺失即视为模型不完整) */
    val REQUIRED = listOf(
      "laya_ml_s256_embeds_npu.tflite",
      "laya_ml_act_head_fp32.tflite",
      "token_embeddings_fp16.bin",
      "laya_ml_scorer.bin",
      "laya_ml_calibration.json",
      "tokenizer.json",
    )

    /** 手动分诊示例(标签 → 文本):覆盖 5 类别 + 困难负例(误报/低危),供引导与自测 */
    val SAMPLES: List<Pair<String, String>> = listOf(
      "正常业务" to "运维例行巡检:周三 03:00 监控平台对已上线的 400 台主机做一次存活与端口探测,结果写入周报,无任何登录尝试,例行变更单已审批。",
      "侦察探测" to "边界防护日志:10 分钟内来源 45.148.10.7 对 22/3306/6379/9200 等 12 个端口各发起 1 次连接,间隔均匀,无认证请求,疑似端口扫描/资产探测。",
      "攻击威胁" to "WAF 告警:来源 103.75.22.18 在 90 秒内向 /admin/login 提交 312 次 POST,用户名轮询 admin/root/test,疑似口令爆破,已触发速率限制。",
      "钓鱼欺诈" to "邮件网关告警:主题【紧急】账户将在 24 小时内停用,发件域 micros0ft-secure.com,正文含短链 bit.ly/3xTt9 引导输入口令,疑似钓鱼邮件。",
      "防御处置" to "SOAR 处置回执:对爆破源 IP 211.90.7.3 已执行 fail2ban 封禁 600s 并通知值班复核,该 IP 后续请求全部 403,攻击流量下降 98%。",
      "困难负例·误报" to "漏扫平台例行任务:对资产段 10.0.0.0/24 执行季度合规扫描,低频探测且无认证尝试,已备案为计划内扫描,无需处置。",
      "困难负例·低危" to "内部员工在测试环境连续 3 次输错 SSH 密码后致电重置,来源为内网办公网段,无横向移动迹象,建议仅记录并提醒。",
    )
  }

  private var engine: LayaRunnerEngine? = null
  private var engineNpu = false

  val workDir: File get() = File(ctx.filesDir, "sec-model")
  val sourceDir: File get() = File(SOURCE_DIR)

  /** 源包(手机存储)是否具备 NPU 全套文件 */
  fun sourceReady(): Boolean = REQUIRED.all { File(sourceDir, it).isFile }

  /** 已拷贝到应用私有目录的模型是否就绪 */
  fun workDirReady(): Boolean = REQUIRED.all { File(workDir, it).isFile }

  /** 实际使用的模型目录:优先直接用手机存储源包(免 650MB 拷贝),否则用应用私有副本 */
  fun modelDir(): File? = when {
    sourceReady() -> sourceDir
    workDirReady() -> workDir
    else -> null
  }

  /** 模型是否就绪(源包或私有副本任一) */
  fun modelReady(): Boolean = modelDir() != null

  /** MTK 天玑且 APK 内置 NPU runner/dispatch 库 → 可用 MDLA NPU */
  fun mtkNpuAvailable(): Boolean {
    val soc = if (android.os.Build.VERSION.SDK_INT >= 31)
      android.os.Build.SOC_MANUFACTURER else ""
    if (!soc.contains("MediaTek", true) && !android.os.Build.HARDWARE.contains("mt6", true)) return false
    val nl = ctx.applicationInfo.nativeLibraryDir
    return File(nl, "libnpu_rt.so").isFile && File(nl, "libLiteRtDispatch_MediaTek.so").isFile
  }

  fun isLoaded(): Boolean = engine != null
  fun backend(): String = if (engine == null) "未加载" else if (engineNpu) "NPU(MTK MDLA)" else "GPU/CPU"

  /** 把 /sdcard 源包模型拷入应用私有目录(onProgress 回传 0-100 进度;大文件,须在后台线程调用)。返回 null 成功,否则错误串。 */
  fun install(onProgress: ((Int) -> Unit)? = null): String? {
    if (!sourceReady()) return "源包缺失: $SOURCE_DIR(需含 laya_ml_s256_embeds_npu.tflite 等 ${REQUIRED.size} 个文件)"
    workDir.mkdirs()
    val names = REQUIRED + listOf("questions.json", "label.txt", "token_embeddings.json")
    val todo = names.filter { name ->
      val s = File(sourceDir, name)
      val d = File(workDir, name)
      s.isFile && (!d.isFile || d.length() != s.length())
    }
    onProgress?.invoke(0)
    var i = 0
    for (name in todo) {
      val s = File(sourceDir, name)
      val d = File(workDir, name)
      try {
        s.copyTo(d, overwrite = true)
      } catch (t: Throwable) {
        return "拷贝 $name 失败: ${t.message}"
      }
      i++
      onProgress?.invoke(i * 100 / maxOf(1, todo.size))
    }
    return null
  }

  /** Java 友好:带进度回调的安装(0-100) */
  fun installWithProgress(cb: java.util.function.IntConsumer): String? = install { cb.accept(it) }

  /** 加载引擎(耗时,后台线程)。返回 null 成功。 */
  fun load(): String? {
    if (engine != null) return null
    val dir = modelDir() ?: return "模型未就绪,请先安装模型"
    val npu = mtkNpuAvailable() && File(dir, "laya_ml_s256_embeds_npu.tflite").isFile
    return try {
      engine = LayaRunnerEngine(ctx, dir, npu = npu)
      engineNpu = npu
      Log.i(TAG, "engine loaded npu=$npu")
      null
    } catch (t: Throwable) {
      engine = null
      Log.w(TAG, "load failed", t)
      "引擎加载失败: ${t.message}"
    }
  }

  fun close() {
    try { engine?.close() } catch (_: Throwable) {}
    engine = null
  }

  /** 问题定义:优先读已拷贝的 questions.json,回退内置定义 */
  fun questionDefs(): List<Pair<String, Map<String, Any?>>> {
    val f = File(modelDir() ?: workDir, "questions.json")
    if (f.isFile) {
      try {
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("questions") ?: JSONArray(f.readText())
        val out = ArrayList<Pair<String, Map<String, Any?>>>()
        for (i in 0 until arr.length()) {
          val q = arr.getJSONObject(i)
          val m = LinkedHashMap<String, Any?>()
          m["type"] = q.getString("type")
          m["instructions"] = q.optString("instructions")
          val crit = q.opt("criteria")
          if (crit != null) m["criteria"] = if (crit is JSONArray) {
            val l = ArrayList<Any?>()
            for (j in 0 until crit.length()) l.add(crit.getString(j))
            l
          } else crit
          out.add(q.optString("qid", "q$i") to m)
        }
        if (out.isNotEmpty()) return out
      } catch (_: Throwable) {
      }
    }
    return listOf(
      "q0" to mapOf("type" to "choice", "instructions" to "这段网络安全相关文本的类别",
        "criteria" to listOf("正常业务", "侦察探测", "攻击威胁", "钓鱼欺诈", "防御处置")),
      "q1" to mapOf("type" to "score", "instructions" to "安全风险严重度1-5",
        "criteria" to listOf("无风险", "轻微", "一般", "较重", "严重")),
      "q2" to mapOf("type" to "noul", "instructions" to "是否需要人工研判"),
    )
  }

  /**
   * 三问分诊。state 必须与训练时逐字对齐:sec 业务 state_key=text,故包成 {"text": "<json-quoted>"}。
   * 返回 {category, severity(0-4), severityLabel, review, confidence..., latencyMs, raw}
   */
  fun triage(text: String): JSONObject {
    val e = engine ?: throw IllegalStateException("引擎未加载")
    val defs = questionDefs()
    val state = "{\"text\": " + JSONObject.quote(text) + "}"
    val answers = JSONObject()
    val t0 = System.nanoTime()
    for ((qid, q) in defs) {
      answers.put(qid, JSONObject(e.answer(state, q, qid)))
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    val out = JSONObject()
    out.put("latencyMs", ms)
    out.put("backend", backend())
    out.put("raw", answers)
    val q0 = answers.optJSONObject("q0")
    if (q0 != null) {
      out.put("category", q0.optString("choice"))
      out.put("categoryConf", q0.optDouble("confidence"))
    }
    val q1 = answers.optJSONObject("q1")
    if (q1 != null) {
      out.put("severity", q1.optDouble("score"))
      val legend = q1.optJSONObject("legend")
      if (legend != null) {
        val idx = Math.round(q1.optDouble("score")).toInt().coerceIn(0, legend.length() - 1)
        out.put("severityLabel", legend.optString(idx.toString()))
      }
    }
    val q2 = answers.optJSONObject("q2")
    if (q2 != null) out.put("review", q2.optDouble("noul"))
    return out
  }

  /** 依据分诊结果给出建议防御动作(教学演示用):封禁/观察/放行 */
  fun suggestAction(r: JSONObject): String {
    val cat = r.optString("category")
    val sev = r.optDouble("severity", 0.0)
    val review = r.optDouble("review", 0.0)
    return when {
      review >= 0.5 || sev >= 3.5 -> "封禁IP并转人工研判"
      cat == "攻击威胁" || cat == "侦察探测" || sev >= 2.0 -> "封禁IP(自动处置)"
      cat == "钓鱼欺诈" -> "标记并从邮件网关拦截"
      cat == "防御处置" -> "记录处置动作"
      else -> "放行/仅记录"
    }
  }
}