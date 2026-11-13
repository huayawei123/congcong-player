# 匆匆影视（Android）

一款运行在 Android 上的影视聚合播放器。采用「原生 WebView 外壳 + 内嵌前端」的结构，
通过公开的苹果 CMS V10 采集接口搜索影片，在应用内完成 HLS 播放与交互控制。

本项目为个人学习 Android WebView 与多媒体播放所写，**不提供、不托管、不分发任何影视内容**，
播放地址全部来自第三方公开接口，应用本身只做聚合与播放。

## 功能

**播放**
- 应用内全屏播放，支持 HLS（m3u8）与 MP4
- 倍速 0.75x ~ 5x（10 档）
- 上一集 / 下一集，跨线路自动衔接
- 单击开关控制条、双击暂停、左右滑动快进、上下滑动调音量
- 横屏播放，返回键先退出横屏回竖屏继续播
- 断点续播，按剧集记录进度

**片头片尾**
- 可设置片头 / 片尾时长，播放到区间时自动跳过或浮出「跳过」按钮
- 进度条上标出片头（紫）/ 片尾（橙）区间
- 配置分「全局默认」与「单片覆盖」两层

**弹幕**
- 本地弹幕：自己发送，按剧集与时间点保存在本机，重看时会重新出现
- B 站弹幕：绑定一次番剧链接，按集数自动拉取对应弹幕
- 弹弹play：填入自己的 AppId/AppSecret 后可作为补充源
- 多个来源按「时间 + 文本」去重合并

**其它**
- 多采集源切换，源健康检测
- 收藏、观看历史
- 深色界面，横竖屏自适应布局

## 技术实现

| 层 | 说明 |
|---|---|
| 容器 | Android `WebView` 加载本地 `assets/app.html`，`addJavascriptInterface` 提供原生桥 |
| 前端 | 单文件 HTML/CSS/JS（约 5900 行），无框架、无构建步骤 |
| 播放 | 优先原生 `<video>`；遇到「根相对路径清单」等原生解析不了的 m3u8，切 hls.js（MSE） |
| 网络 | 原生 `HttpURLConnection` 代理请求，绕过 WebView 的跨域限制；支持自定义请求头与 raw deflate 解压 |
| 构建 | `build_apk.py` 直接调用 `aapt2 / javac / d8 / zipalign / apksigner`，不依赖 Gradle |

### 与弹弹play API 的关系

应用调用弹弹play 开放平台的以下接口，**仅用于获取弹幕评论**：

- `GET /api/v2/search/episodes` —— 按番剧名检索，拿到 `episodeId`
- `GET /api/v2/comment/{episodeId}` —— 取该集的弹幕列表

调用特点：

1. 只在用户主动打开某部剧、且用户自行填入了 AppId/AppSecret 时才发起请求；
2. 请求带 `X-AppId` / `X-Timestamp` / `X-Signature` 三个头，
   签名为 `base64(sha256(AppId + Timestamp + Path + AppSecret))`，在原生层计算；
3. 拿到的弹幕只在内存中使用，**不落盘、不上传、不二次分发**；
4. 没有可匹配的剧集时只记录一次，不会重复请求；
5. AppSecret 仅保存在用户自己设备的 `localStorage`，不上传到任何服务器。

## 构建

环境要求：JDK 8+、Android SDK（`android.jar` 与 build-tools 34）、Python 3.8+。

```bash
cd android
python build_apk.py          # 影视版
python build_apk.py --live   # 直播版
```

产物：`Congcong-Movie.apk` / `Congcong-Live.apk`。

签名使用本地 keystore（**不在仓库内**），首次构建时会提示生成。

## 目录结构

```
android/
├── assets/
│   ├── app.html              # 前端全部逻辑（UI、播放、弹幕、解析）
│   └── hls.min.js            # MSE 兜底播放
├── java/com/congcong/player/
│   └── MainActivity.java     # WebView 外壳、原生桥、设置面板
├── res/                      # 图标与字符串
├── AndroidManifest.xml
└── build_apk.py              # 一键构建脚本
```

## 免责声明

- 本项目仅供个人学习 Android 开发与流媒体播放技术使用。
- 应用不存储、不转码、不分发任何影视文件；所有播放地址均来自第三方公开采集接口，
  其可用性与合法性由该第三方负责。
- 请勿将本项目用于任何商业用途。若相关权利人认为有侵权内容，请向内容提供方主张权利。
- 使用者应自行遵守所在地法律法规。

## 许可

仅供学习交流，不提供任何形式的担保。
