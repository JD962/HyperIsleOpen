#!/usr/bin/env bash
# HyperIsleOpen 发布脚本：一条命令完成「版本号提升 → 提交 → 打 tag → 测试+构建 → 推送源码 → 创建 GitHub Release 并上传 APK」。
#
# 用法（bash 环境，Windows 下经 Git Bash 运行）：
#   ./release.sh 1.0.24            # 正式版：tag=v1.0.24，Release 标记 Latest
#   ./release.sh 1.0.24-beta1      # 测试版：tag=v1.0.24-beta1，Release 标记 Pre-release
#
# 约定：
#   - tag 名称即版本名（v<versionName>），与 app/build.gradle.kts 的 versionName 一致；
#   - 版本名带 "-" 后缀视为测试版（如 -beta1 / -rc1 / -test1），否则为正式版；
#   - versionCode 每次发布 +1；
#   - 源码随每次发布推送；构建产物（APK）仅作为 Release 附件上传，不入仓库；
#   - 依赖 gh CLI 已授权（gh auth login）；签名属性经用户级 ~/.gradle/gradle.properties
#     提供（signing.storePassword / signing.keyAlias / signing.keyPassword），不入库。
#
# 安全：本脚本与仓库均不含任何密钥/令牌，全部经 gh 与 Gradle 属性通道获取。
set -euo pipefail

die() { echo "ERROR: $*" >&2; exit 1; }

[ $# -eq 1 ] || die "usage: $0 <version-name>   e.g. $0 1.0.24 | $0 1.0.24-beta1"
VERSION="$1"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "invalid version: $VERSION (expect 1.2.3 or 1.2.3-beta1)"
TAG="v$VERSION"

command -v gh >/dev/null 2>&1 || die "gh CLI not found (https://cli.github.com)"
gh auth status >/dev/null 2>&1 || die "gh not authenticated: run 'gh auth login'"
grep -q "signing.storePassword" "$HOME/.gradle/gradle.properties" 2>/dev/null \
  || die "signing properties missing in ~/.gradle/gradle.properties (store/key password + alias)"

[ -n "$(git status --porcelain)" ] && die "working tree dirty: commit or stash first"
git rev-parse "$TAG" >/dev/null 2>&1 && die "tag $TAG already exists"
git remote get-url origin >/dev/null 2>&1 || die "origin remote not configured"

# 1. 版本号提升（versionCode +1，versionName 写入新版本）
GRADLE_FILE="app/build.gradle.kts"
CURRENT_CODE=$(sed -n 's/^        versionCode = \([0-9]*\)$/\1/p' "$GRADLE_FILE")
[ -n "$CURRENT_CODE" ] || die "versionCode not found in $GRADLE_FILE"
NEW_CODE=$((CURRENT_CODE + 1))
CURRENT_NAME=$(sed -n 's/^        versionName = "\([^"]*\)"$/\1/p' "$GRADLE_FILE")
echo "version: $CURRENT_NAME ($CURRENT_CODE) -> $VERSION ($NEW_CODE)"
sed -i "s/^        versionCode = .*/        versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/^        versionName = \".*\"/        versionName = \"$VERSION\"/" "$GRADLE_FILE"

# 2. 提交 + 打 tag（tag 名称即版本名）
git add "$GRADLE_FILE"
git commit -q -m "Release $TAG"
git tag -a "$TAG" -m "HyperIsleOpen $TAG"

# 3. 单元测试 + release 构建（任一失败即中断，tag/提交可回退）
echo "==> unit tests"
./gradlew testDebugUnitTest --console=plain -q
echo "==> assembleRelease"
./gradlew assembleRelease --console=plain -q
APK="app/build/outputs/apk/release/HyperIsleOpen-release.apk"
[ -f "$APK" ] || die "apk not found: $APK"

# 4. 推送源码与 tag
echo "==> push source and tag"
git push -q origin main
git push -q origin "$TAG"

# 5. 创建 Release 并上传 APK（正式版 = Latest；测试版 = Pre-release）
echo "==> create GitHub release"
if [[ "$VERSION" == *-* ]]; then
  gh release create "$TAG" "$APK" --title "HyperIsleOpen $TAG（测试版）" --prerelease --generate-notes
else
  gh release create "$TAG" "$APK" --title "HyperIsleOpen $TAG" --latest --generate-notes
fi

REPO_URL=$(gh repo view --json url -q .url)
echo "==> done: $REPO_URL/releases/tag/$TAG"
