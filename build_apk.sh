#!/bin/bash
# ============================================================================
# TVBoxOS-Live APK 一键构建脚本
# ============================================================================
# 使用方法:
#   chmod +x build_apk.sh
#   ./build_apk.sh [debug|release] [abi]
#
# 参数:
#   构建类型: debug（默认） | release
#   ABI:      all（默认） | armeabi-v7a(arm32) | arm64-v8a(arm64)
#             | x86 | x86_64 | 也支持逗号分隔组合
#
# 示例:
#   ./build_apk.sh release armeabi-v7a   # 仅 arm32 的 release 包
#   ./build_apk.sh debug arm64-v8a       # 仅 arm64 的 debug 包
#   ./build_apk.sh release all           # 全 ABI 通用包
#
# 前置要求:
#   1. JDK 11+
#   2. Android SDK (compileSdk 33)
#   3. 设置 ANDROID_HOME 环境变量
# ============================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m'

# 项目信息
PROJECT_NAME="TVBoxOS-Live"
VERSION="1.2.0"

echo -e "${PURPLE}"
echo "╔═══════════════════════════════════════════╗"
echo "║        TVBoxOS-Live APK 构建工具           ║"
echo "║        v${VERSION}                              ║"
echo "╚═══════════════════════════════════════════╝"
echo -e "${NC}"

# 构建类型
BUILD_TYPE=${1:-debug}
# 目标 ABI（all=全架构；armeabi-v7a=仅arm32；arm64-v8a=仅arm64）
TARGET_ABI=${2:-all}
echo -e "${BLUE}[信息]${NC} 构建类型: ${BUILD_TYPE}"
echo -e "${BLUE}[信息]${NC} 目标 ABI:  ${TARGET_ABI}"

# 检查 Java 环境
echo -e "${BLUE}[检查]${NC} Java 环境..."
if ! command -v java &> /dev/null; then
    echo -e "${RED}[错误]${NC} 未找到 Java，请安装 JDK 11+"
    echo "  推荐: https://adoptium.net/temurin/releases/"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1)
echo -e "${GREEN}[OK]${NC} $JAVA_VERSION"

# 检查 ANDROID_HOME
echo -e "${BLUE}[检查]${NC} Android SDK..."
if [ -z "$ANDROID_HOME" ]; then
    # 尝试常见路径
    if [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "/usr/local/android-sdk" ]; then
        export ANDROID_HOME="/usr/local/android-sdk"
    else
        echo -e "${RED}[错误]${NC} 未找到 Android SDK"
        echo "  请设置 ANDROID_HOME 环境变量"
        echo "  export ANDROID_HOME=/path/to/android/sdk"
        exit 1
    fi
fi
echo -e "${GREEN}[OK]${NC} ANDROID_HOME=$ANDROID_HOME"

# 检查所需的 SDK 版本
echo -e "${BLUE}[检查]${NC} SDK Platform 33..."
if [ ! -d "$ANDROID_HOME/platforms/android-33" ]; then
    echo -e "${YELLOW}[警告]${NC} 未安装 SDK Platform 33，尝试自动安装..."
    if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
        $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-33" "build-tools;33.0.2"
    else
        echo -e "${RED}[错误]${NC} 请手动安装 SDK Platform 33"
        echo "  sdkmanager 'platforms;android-33' 'build-tools;33.0.2'"
        exit 1
    fi
fi
echo -e "${GREEN}[OK]${NC} SDK Platform 33 已就绪"

# 确保 Gradle Wrapper 存在
echo -e "${BLUE}[检查]${NC} Gradle Wrapper..."
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo -e "${YELLOW}[修复]${NC} 生成 Gradle Wrapper..."
    if command -v gradle &> /dev/null; then
        gradle wrapper --gradle-version 7.5
    else
        echo -e "${YELLOW}[提示]${NC} 正在下载 Gradle Wrapper..."
        mkdir -p gradle/wrapper
        WRAPPER_URL="https://services.gradle.org/distributions/gradle-7.5-bin.zip"
        # 使用项目自带的 wrapper properties 即可
        # 首次运行 gradlew 时会自动下载
        echo -e "${YELLOW}[提示]${NC} Gradle 将在首次构建时自动下载"
    fi
fi

# 授予 gradlew 执行权限
chmod +x gradlew 2>/dev/null || true

# 清理旧构建
echo -e "${BLUE}[构建]${NC} 清理旧构建产物..."
./gradlew clean 2>/dev/null || {
    echo -e "${YELLOW}[提示]${NC} 首次构建，Gradle 正在下载依赖..."
}

# 开始构建
echo ""
echo -e "${BLUE}[构建]${NC} 正在构建 ${BUILD_TYPE} APK..."
echo -e "${BLUE}[构建]${NC} 这可能需要几分钟，请耐心等待..."
echo ""

START_TIME=$(date +%s)

# 把 ABI 传给 Gradle（build.gradle 里通过 project.property('targetAbi') 读取）
GRADLE_ARGS="--no-daemon -PtargetAbi=${TARGET_ABI}"

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease ${GRADLE_ARGS}
    # 优先取已签名产物，未签名回退
    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    else
        APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    fi
else
    ./gradlew assembleDebug ${GRADLE_ARGS}
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
fi

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

# 检查构建结果
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)

    # 复制到项目根目录方便获取，文件名包含 ABI 便于区分
    ABI_TAG="${TARGET_ABI}"
    # armeabi-v7a → arm32; arm64-v8a → arm64（更直观）
    case "$TARGET_ABI" in
        armeabi-v7a) ABI_TAG="arm32" ;;
        arm64-v8a)   ABI_TAG="arm64" ;;
        all)         ABI_TAG="universal" ;;
    esac
    OUTPUT_NAME="${PROJECT_NAME}-${VERSION}-${BUILD_TYPE}-${ABI_TAG}.apk"
    cp "$APK_PATH" "./$OUTPUT_NAME"

    echo ""
    echo -e "${GREEN}╔═══════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║          ✅ 构建成功！                     ║${NC}"
    echo -e "${GREEN}╚═══════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  ${BLUE}APK 文件:${NC} $OUTPUT_NAME"
    echo -e "  ${BLUE}文件大小:${NC} $APK_SIZE"
    echo -e "  ${BLUE}构建耗时:${NC} ${ELAPSED} 秒"
    echo -e "  ${BLUE}输出路径:${NC} $(pwd)/$OUTPUT_NAME"
    echo ""

    if [ "$BUILD_TYPE" = "release" ]; then
        echo -e "${YELLOW}[提示]${NC} Release APK 未签名，请使用以下命令签名:"
        echo "  apksigner sign --ks your-keystore.jks --out signed.apk $OUTPUT_NAME"
        echo ""
    fi

    echo -e "${GREEN}[完成]${NC} 你可以通过 adb install $OUTPUT_NAME 安装到设备"
else
    echo ""
    echo -e "${RED}╔═══════════════════════════════════════════╗${NC}"
    echo -e "${RED}║          ❌ 构建失败                       ║${NC}"
    echo -e "${RED}╚═══════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${RED}[错误]${NC} 请检查上方的错误日志"
    echo -e "${YELLOW}[提示]${NC} 常见问题:"
    echo "  1. 确保 ANDROID_HOME 正确设置"
    echo "  2. 确保安装了 SDK Platform 33"
    echo "  3. 确保安装了 Build Tools 33.0.2"
    echo "  4. 确保网络连接正常（首次构建需下载依赖）"
    exit 1
fi
