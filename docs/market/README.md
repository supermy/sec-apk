# 市场发布清单(sec-apk / 攻防实验台)

> 发版只做一次构建:一律走 `bash release.sh`(升版本 → 重建 → badging/签名校验 → `release/` 产物),
> 切勿"先升版本后复用旧包"(laya-test v1.4.13-21 版本错位的教训)。

## 0. 每次发版(必做)

1. `bash release.sh [-v X.Y.Z]` → `release/seclab-X.Y.Z.apk` + `.sha256`
2. `CHANGELOG.md` 补对应条目;`git add -A && git commit && git push`
3. `git tag vX.Y.Z && git push origin vX.Y.Z`
4. `gh release create vX.Y.Z release/seclab-X.Y.Z.apk --notes "见 CHANGELOG"`
5. F-Droid 自建仓库增量发布(见第 3 节)

## 1. 物料清单(各市场通用)

| 物料 | 状态 | 说明 |
|---|---|---|
| 应用名 | 就绪 | `res/values{,-en}/strings.xml` 的 `app_name`(中:攻防实验台 / 英:Sec Lab) |
| 图标 512×512 | 就绪 | `tools/make_icon.py` 生成,从 mipmap 导出 512 版备用 |
| 一句话简介 | 就绪 | 见 `listings.md`(各市场 15~40 字限制微调) |
| 完整描述 | 就绪 | `listings.md`(zh/en) |
| 更新说明模板 | 就绪 | `listings.md` 末节 |
| 截图 4~6 张 | **待补** | 真机截屏:演练页(事件流可见)/SSH目标页/分诊结果/NPU 状态卡;1080×2400 内、去状态栏敏感信息 |
| 隐私说明 | **待补** | 无账号、无后台上报、数据全在本机;需发布为可公网访问的隐私政策页(GitHub Pages 即可) |
| 软件著作权/ICP 备案 | **用户侧** | 国内部分市场(华为/小米等)需软著或备案证明,按市场要求提交 |
| targetSdk 合规 | **风险项** | 当前 `targetSdkVersion=28`,部分市场要求 ≥30/33;升前须在真机验证外置存储读写(MANAGE_EXTERNAL_STORAGE 路径)与明文回环流量行为 |

## 2. 国内主流市场要点

- **华为应用市场**:需实名认证 + 软著/授权书;安全类 App 可能要求补充说明材料(说明"仅本机回环教学演示")。
- **小米/OPPO/vivo**:同样实名 + 软著;描述中明确"教育演示用途,不面向真实攻击"。
- **腾讯应用宝**:审核较严,准备功能演示视频(`demo/seclab-demo.mp4` 可复用)。
- 通用建议:描述中给出"①-⑥ 演练流程 + 仅回环"安全声明,减少安全类误判下架风险。

## 3. F-Droid 自建仓库(GitHub Pages)

与 laya-test 同套路:

1. `git worktree add ../sec-apk-pages gh-pages`(**必须独立 worktree**,勿把 fdroid/ 提交进 master)
2. `fdroid update`(fdroidserver 2.4.5,签名与 Release APK 同一 keystore `k90`)
3. 将 `bash release.sh` 产物放入 repo 目录后 `fdroid update -c && git add -A && git commit && git push`
4. 仓库地址形如 `https://supermy.github.io/sec-apk/fdroid/repo`,客户端添加后自动升级

## 4. 待办(发布前)

- [ ] 真机截图(4~6 张)并入 `demo/` 或 `docs/market/shots/`
- [ ] 隐私政策页(GitHub Pages)+ 各市场回填链接
- [ ] targetSdk 升级评估(≥30)并在真机回归存储/网络/语言切换
- [ ] 英文描述校对(海外/Edge 分发可选)
