"""匆匆影视安卓播放器一键构建（不依赖 Gradle / 不联网下载 SDK）。

流程：aapt2 compile/link → javac → d8 → 追加 classes.dex → zipalign → keytool → apksigner。
产物：D:\\Congcong\\Congcong-Movie.apk
"""
import glob
import io
import os
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib

ROOT = r"D:\Congcong"
PROJ = os.path.join(ROOT, "android")
BUILD = os.path.join(PROJ, "build")
SDK = r"C:\android-sdk"
JDK = r"C:\jdk-17.0.16+8"
BT = os.path.join(SDK, "build-tools", "34.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
AAPT = os.path.join(BT, "aapt.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
D8_JAR = os.path.join(BT, "lib", "d8.jar")
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar")
JAVA = os.path.join(JDK, "bin", "java.exe")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")
# 两个版本用不同的包名，可以并存在同一台手机上（装直播版不会覆盖影视版）
LIVE = "--live" in sys.argv
APP_ID = "com.congcong.player.live" if LIVE else "com.congcong.player"
APP_LABEL = "匆匆直播" if LIVE else "匆匆影视"
OUT_APK = os.path.join(ROOT, "Congcong-Live.apk" if LIVE else "Congcong-Movie.apk")
# 签名密钥放在 build/ 之外，避免每次重建被 rmtree 清掉导致升级无法覆盖安装
KEYSTORE = os.path.join(PROJ, "congcong.jks")
"""签名口令从 signing.properties 读，不写死在代码里。
   该文件已在 .gitignore 中排除，避免把口令提交到公开仓库。
   读不到时回落到示例值，方便别人 clone 后先跑通构建（他们需要自建 keystore）。"""
STOREPASS = ""      # 只能由 signing.properties 提供，源码里不留口令
try:
    _sp = os.path.join(PROJ, "signing.properties")
    if os.path.exists(_sp):
        with io.open(_sp, encoding="utf-8") as _f:
            for _line in _f:
                _line = _line.strip()
                if _line.startswith("storepass="):
                    STOREPASS = _line.split("=", 1)[1].strip()
except Exception:
    pass


def require_storepass():
    """没有口令就不要往下走：与其让 apksigner 报一句看不懂的错，
    不如直接说清楚该在哪个文件里填什么。"""
    if STOREPASS:
        return
    raise SystemExit(
        "缺少签名口令。请在 %s 里写一行：\n    storepass=你的口令\n"
        "（该文件已被 .gitignore 排除，不会进仓库）" % os.path.join(PROJ, "signing.properties"))


def run(cmd, label):
    print("\n>>> " + label)
    print("    " + " ".join(str(x) for x in cmd[:6]) + (" ..." if len(cmd) > 6 else ""))
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print("STDOUT:\n" + (r.stdout or ""))
        print("STDERR:\n" + (r.stderr or ""))
        raise RuntimeError(label + " 失败，exit=" + str(r.returncode))
    if r.stdout and r.stdout.strip():
        print("    " + r.stdout.strip()[:800].replace("\n", "\n    "))
    return r


def make_fallback_icon(path):
    """无源图标时生成一张 192x192 渐变 PNG（纯标准库手写 PNG）。"""
    w = h = 192

    def pixel(x, y):
        t = (x + y) / (w + h)
        return (int(124 - 60 * t), int(92 - 40 * t), int(255 - 90 * t))

    raw = b"".join(b"\x00" + bytes(pixel(x, y)) for y in range(h) for x in range(w))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def ensure_icon():
    dst_dir = os.path.join(PROJ, "res", "mipmap-xxxhdpi")
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, "ic_launcher.png")
    src = os.path.join(ROOT, "public", "icons", "icon-192.png")
    if os.path.exists(src):
        shutil.copyfile(src, dst)
        print("图标: 复用 " + src)
    else:
        make_fallback_icon(dst)
        print("图标: 生成渐变占位图 " + dst)


def main():
    for p in [AAPT2, ANDROID_JAR, JAVA, JAVAC, KEYTOOL, D8_JAR, APKSIGNER_JAR, ZIPALIGN, AAPT]:
        if not os.path.exists(p):
            raise RuntimeError("缺少构建工具: " + p)

    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    classes = os.path.join(BUILD, "classes")
    dexdir = os.path.join(BUILD, "dex")
    os.makedirs(classes)
    os.makedirs(dexdir)
    ensure_icon()

    res_zip = os.path.join(BUILD, "res.zip")
    base_apk = os.path.join(BUILD, "base.apk")
    dexed_apk = os.path.join(BUILD, "dexed.apk")
    aligned_apk = os.path.join(BUILD, "aligned.apk")
    keystore = KEYSTORE
    manifest = os.path.join(PROJ, "AndroidManifest.xml")
    assets = os.path.join(PROJ, "assets")

    link_extra = []
    if LIVE:
        # 1) 改包名：aapt2 --rename-manifest-package 只改 APK 里的 package 属性，
        #    Java 侧仍是 com.congcong.player，R 类不受影响
        # 2) activity 必须写成全限定名，否则 ".MainActivity" 会被拼成
        #    com.congcong.player.live.MainActivity（不存在，桌面图标点不开）
        # 3) 标签写死成「匆匆直播」，和影视版区分
        m_src = io.open(manifest, encoding="utf-8").read()
        m_src = m_src.replace('android:name=".MainActivity"',
                              'android:name="com.congcong.player.MainActivity"')
        m_src = m_src.replace('android:label="@string/app_name"',
                              'android:label="' + APP_LABEL + '"')
        manifest = os.path.join(BUILD, "AndroidManifest.xml")
        io.open(manifest, "w", encoding="utf-8").write(m_src)
        link_extra = ["--rename-manifest-package", APP_ID]

        # assets 复制一份再打开直播开关，源码保持影视版的默认值 false
        assets = os.path.join(BUILD, "assets_live")
        shutil.copytree(os.path.join(PROJ, "assets"), assets)
        a_path = os.path.join(assets, "app.html")
        a_src = io.open(a_path, encoding="utf-8").read()
        if "var LIVE_EDITION = false;" not in a_src:
            raise RuntimeError("app.html 里找不到 LIVE_EDITION 开关")
        io.open(a_path, "w", encoding="utf-8").write(
            a_src.replace("var LIVE_EDITION = false;", "var LIVE_EDITION = true;"))
        print("直播版：包名 %s / 标签 %s" % (APP_ID, APP_LABEL))
    java_src = os.path.join(PROJ, "java", "com", "congcong", "player", "MainActivity.java")

    # 1. 编译资源
    run([AAPT2, "compile", "--dir", os.path.join(PROJ, "res"), "-o", res_zip], "aapt2 compile res")

    # 2. 链接生成基础 APK（同时打包 assets/app.html）
    run([AAPT2, "link", "-o", base_apk, "-I", ANDROID_JAR,
         "--manifest", manifest, "-A", assets, "-R", res_zip,
         "--min-sdk-version", "24", "--target-sdk-version", "34",
         "--auto-add-overlay"] + link_extra, "aapt2 link")

    # 3. 编译 Java（android.jar 作为 bootclasspath，不用 Gradle）
    run([JAVAC, "-encoding", "UTF-8", "--release", "8", "-nowarn",
         "-cp", ANDROID_JAR, "-d", classes, java_src], "javac MainActivity.java")

    class_files = glob.glob(os.path.join(classes, "**", "*.class"), recursive=True)
    if not class_files:
        raise RuntimeError("javac 未生成任何 class 文件")

    # 4. D8 → classes.dex
    run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--lib", ANDROID_JAR, "--min-api", "24", "--output", dexdir]
        + class_files, "d8 -> classes.dex")
    dex_file = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex_file):
        raise RuntimeError("d8 未生成 classes.dex")

    # 5. 把 classes.dex 追加进 APK（STORED，便于 zipalign 对齐）
    with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex", compress_type=zipfile.ZIP_STORED)
    shutil.copyfile(base_apk, dexed_apk)
    print("追加 classes.dex: %d bytes" % os.path.getsize(dex_file))

    # 6. 对齐
    run([ZIPALIGN, "-f", "4", dexed_apk, aligned_apk], "zipalign")

    # 7. 生成自签名证书（已存在则复用，保证升级可覆盖安装）
    require_storepass()          # 口令缺失就在这里停下，别等 apksigner 报难懂的错
    if os.path.exists(keystore):
        print("\n>>> 复用已有签名证书: " + keystore)
    else:
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", keystore,
             "-alias", "congcong", "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10950", "-storepass", STOREPASS, "-keypass", STOREPASS,
             "-dname", "CN=Congcong Movie, OU=Congcong, O=Congcong, L=CN, ST=CN, C=CN"],
            "keytool 生成签名证书")

    # 8. v1+v2+v3 签名
    run([JAVA, "-jar", APKSIGNER_JAR, "sign",
         "--ks", keystore, "--ks-key-alias", "congcong",
         "--ks-pass", "pass:" + STOREPASS, "--key-pass", "pass:" + STOREPASS,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         "--out", OUT_APK, aligned_apk], "apksigner 签名")

    # 9. 验证
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", OUT_APK], "apksigner verify")
    r = run([AAPT, "dump", "badging", OUT_APK], "aapt dump badging")
    for line in (r.stdout or "").splitlines():
        if line.startswith("package:") or "application-label" in line or "sdkVersion" in line:
            print("    " + line[:220])

    print("\n==== 构建完成 ====")
    print("版本: " + ("直播版（含直播版面）" if LIVE else "影视版"))
    print("包名: " + APP_ID)
    print("APK: " + OUT_APK)
    print("大小: %.2f MB" % (os.path.getsize(OUT_APK) / 1024 / 1024))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("\n构建失败: " + str(e))
        sys.exit(1)
