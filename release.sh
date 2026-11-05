#!/usr/bin/env bash
# ออกเวอร์ชันใหม่: build APK ที่เซ็นแล้ว แล้วสร้าง GitHub Release แนบไฟล์
#
#   1) แก้ appVersionName ใน gradle.properties แล้ว commit + push
#   2) ./release.sh "รายละเอียดการอัปเดต"
#
# แอปที่ติดตั้งอยู่เช็ค release ล่าสุดที่นี่เอง (ไม่ต้องตั้ง Webhook URL) และเว็บทุกตัว (หน้าข้อมูลดิบมือถือ)
# ก็ดึงปุ่มดาวน์โหลดจากที่นี่ที่เดียว
# ต้องมี gh (gh auth login) และ keystore/keystore.properties
set -euo pipefail
cd "$(dirname "$0")"

NOTES="${1:-}"
[ -n "$NOTES" ] || { echo "ใส่รายละเอียดการอัปเดต: ./release.sh \"แก้การอ่านแจ้งเตือน KBank\""; exit 1; }

VERSION=$(grep '^appVersionName=' gradle.properties | cut -d= -f2)
TAG="v$VERSION"
APK="dist/NotifyHook-$TAG.apk"

[ -z "$(git status --porcelain)" ] || { echo "มีไฟล์ที่ยังไม่ได้ commit — commit ก่อนออกเวอร์ชัน"; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{u}')" ] || { echo "branch ยังไม่ตรงกับ origin — push ก่อน"; exit 1; }
if gh release view "$TAG" >/dev/null 2>&1; then
    echo "มี release $TAG แล้ว — เพิ่ม appVersionName ใน gradle.properties ก่อน"; exit 1
fi

./gradlew :app:dist --console=plain -q
gh release create "$TAG" "$APK" --target "$(git rev-parse HEAD)" --title "NotifyHook $TAG" --notes "$NOTES"
echo "ออก NotifyHook $TAG แล้ว"
