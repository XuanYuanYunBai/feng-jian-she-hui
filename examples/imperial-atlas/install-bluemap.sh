#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ! -d "$1" ]]; then
    echo "用法：install-bluemap.sh 已有的测试服目录" >&2
    exit 2
fi
plugins="$1/plugins"
mkdir -p "$plugins"
name="bluemap-5.29-paper.jar"
digest="7bf475936c26107d476c63151c76d5b49e865eebeada12c9e7d92155de877b9f"
verify() { printf '%s  %s\n' "$digest" "$1" | sha256sum --check --status; }
for existing in "$plugins"/*.[jJ][aA][rR]; do
    [[ -e "$existing" ]] || continue
    if [[ "${existing##*/}" =~ ^[Bb][Ll][Uu][Ee][Mm][Aa][Pp](-[0-9].*)?\.[jJ][aA][rR]$ ]]; then
        if verify "$existing"; then
            echo "BlueMap 5.29 已安装于 $existing"
            exit 0
        fi
        echo "请先核对测试服现有的 BlueMap 版本：$existing" >&2
        exit 1
    fi
done
download=$(mktemp "$plugins/.bluemap-5.29.XXXXXX")
trap 'rm -f -- "$download"' EXIT
curl --fail --location --silent --show-error --retry 2 \
    "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v5.29/$name" --output "$download"
verify "$download" || { echo "BlueMap 文件校验失败" >&2; exit 1; }
chmod 644 "$download"
mv -- "$download" "$plugins/$name"
echo "已安装 $plugins/$name"
