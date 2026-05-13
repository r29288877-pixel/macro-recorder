#!/bin/bash
# 方案一：本機打包腳本（一行指令）
# 需要：Java 17+ 已安裝
# 執行方式：bash local.build.sh

echo "🔨 開始打包 Macro Recorder APK..."
chmod +x gradlew
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    echo ""
    echo "✅ 打包成功！"
    echo "📦 APK 位置：$APK_PATH"
    echo ""
    echo "📱 安裝到手機："
    echo "   adb install $APK_PATH"
    echo "   或直接將 APK 傳到手機安裝"
else
    echo "❌ 打包失敗，請確認 Java 17+ 已安裝"
    echo "   安裝 Java：https://adoptium.net/"
fi
