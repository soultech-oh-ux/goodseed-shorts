#!/usr/bin/env python3
"""릴리스 산출물 이름을 고정해 만든다 (앱 안 업데이터가 찾는 이름 규칙).

  dist/goodseed-shorts-X.Y.Z.apk
  dist/goodseed-shorts-X.Y.Z.apk.sha256      ← 내용: "<해시 64자리>  goodseed-shorts-X.Y.Z.apk" (두 칸 띄움, 줄바꿈 1개)

사용:
  python tools/make_release_files.py <서명된 apk 경로> [--out dist] [--version X.Y.Z]
      버전을 안 주면 android/app/build.gradle.kts 의 versionName 을 읽는다.
      apk 를 이름 규칙대로 복사하고, 해시 파일을 만들고, 다시 읽어 검증한다. 잘못된 이름의 옛 해시 파일(X.Y.Z.sha256)은 지운다.
  python tools/make_release_files.py --check [--out dist] [--version X.Y.Z]
      이미 있는 dist 파일의 이름·형식·해시가 규칙에 맞는지만 검사한다(맞으면 종료코드 0).
"""
import argparse
import hashlib
import os
import re
import shutil
import sys

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
LINE_RE = re.compile(r"^([0-9a-f]{64})  (goodseed-shorts-\d+\.\d+\.\d+\.apk)\n$")
HERE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(HERE, "..", "android", "app", "build.gradle.kts")


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def version_from_gradle():
    with open(GRADLE, encoding="utf-8") as f:
        m = re.search(r'versionName\s*=\s*"([^"]+)"', f.read())
    return m.group(1) if m else None


def names(version):
    apk = "goodseed-shorts-%s.apk" % version
    return apk, apk + ".sha256"


def check(out, version):
    apk, sha = names(version)
    apk_path, sha_path = os.path.join(out, apk), os.path.join(out, sha)
    for p in (apk_path, sha_path):
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            return "없거나 비어 있음: " + p
    with open(sha_path, "rb") as f:
        raw = f.read()
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError:
        return "해시 파일에 ASCII 가 아닌 글자가 있음"
    m = LINE_RE.match(text)
    if not m:
        return "해시 파일 형식이 '해시  파일명' 한 줄이 아님: %r" % text[:120]
    if m.group(2) != apk:
        return "해시 파일 안의 파일명이 다름: %s != %s" % (m.group(2), apk)
    if m.group(1) != sha256_of(apk_path):
        return "해시가 apk 와 다름"
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", nargs="?", help="서명된 apk 경로")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "dist"))
    ap.add_argument("--version")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    version = a.version if a.version is not None else version_from_gradle()
    if not version or not VERSION_RE.match(version):
        print("버전이 X.Y.Z 형식이 아님: %r" % version, file=sys.stderr)
        return 2
    out = os.path.abspath(a.out)
    if a.check:
        err = check(out, version)
        print("OK" if err is None else "실패: " + err)
        return 0 if err is None else 1
    if not a.apk or not os.path.isfile(a.apk) or os.path.getsize(a.apk) == 0:
        print("apk 파일이 없거나 비어 있음: %r" % a.apk, file=sys.stderr)
        return 2
    os.makedirs(out, exist_ok=True)
    apk, sha = names(version)
    dst = os.path.join(out, apk)
    if os.path.abspath(a.apk) != dst:
        shutil.copyfile(a.apk, dst)
    with open(os.path.join(out, sha), "w", encoding="ascii", newline="\n") as f:
        f.write("%s  %s\n" % (sha256_of(dst), apk))
    wrong = os.path.join(out, "goodseed-shorts-%s.sha256" % version)   # 0.4.0 에서 잘못 만들었던 이름
    if os.path.isfile(wrong):
        os.remove(wrong)
    err = check(out, version)
    if err:
        print("검증 실패: " + err, file=sys.stderr)
        return 1
    print(open(os.path.join(out, sha), encoding="ascii").read().rstrip("\n"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
