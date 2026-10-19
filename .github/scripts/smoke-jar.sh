#!/usr/bin/env bash
# =============================================================================
# forge 产物 jar 冒烟门禁
# 对应 AGENT.md 铁律：§2「compileOnly 鹊桥接口……绝不 shade」、
#                   §3「元数据模板 ReplaceTokens（@token@）渲染」
# 断言（任一失败立即退出非零并打印原因）：
#   1. 文件名匹配 ^queqiao_map_web-forge-<mod版本>+mc<MC版本>.jar$  【REQ-ART-004】
#      （forge/build.gradle：archivesBaseName=${mod_id}-${loader}，
#        archiveFileName=${base}-${mod_version}+mc<mc>.jar）
#   2. 含 META-INF/mods.toml 与 pack.mcmeta
#   3. mods.toml 无 '@' 残留（ReplaceTokens 未渲染必现 @token@）【REQ-ART-001】；
#      存在 modId="queqiao_map_web" 行
#   4. pack.mcmeta 是合法 JSON（jq . 不报错）
#   5. 含 net/yumezawa/queqiao_map_web/ModLifecycle.class（common 已并进 jar）【REQ-ART-002】
#      与 net/yumezawa/queqiao_map_web/forge/QueqiaoMapWeb.class（Forge 入口）
#   6. 不含任何 com/github/theword/ 条目（绝不 shade 鹊桥）【REQ-ART-002】
#   7. META-INF/MANIFEST.MF 含 Minecraft-Mod-ID: queqiao_map_web  【REQ-ART-003】
# 依赖：ubuntu-latest 自带 unzip 与 jq。
# =============================================================================
set -euo pipefail

jar="${1:-}"
if [ -z "$jar" ] || [ ! -f "$jar" ]; then
  echo "用法: $0 <jar 路径>（文件必须存在）" >&2
  exit 1
fi
base="$(basename "$jar")"

fail() {
  echo "SMOKE FAIL [$base]: $1" >&2
  exit 1
}

# ---- 1. 产物命名（AGENT.md §3 / forge/build.gradle 命名约定）【REQ-ART-004】 --
if [[ ! "$base" =~ ^queqiao_map_web-forge-[^/]+[+]mc[0-9.]+[.]jar$ ]]; then
  fail "文件名不符合 queqiao_map_web-forge-<mod版本>+mc<MC版本>.jar：$base"
fi

# ---- 条目清单（后续断言共用） ----------------------------------------------
listing="$(unzip -Z1 "$jar")"

# ---- 2. 元数据条目存在 ------------------------------------------------------
for entry in META-INF/mods.toml pack.mcmeta; do
  printf '%s\n' "$listing" | grep -qxF "$entry" || fail "jar 缺少条目 $entry"
done

# ---- 3. mods.toml 渲染完成 + modId 正确 ------------------------------------
mods_toml="$(unzip -p "$jar" META-INF/mods.toml)"
if printf '%s' "$mods_toml" | grep -q '@'; then
  # ReplaceTokens 未渲染时模板里的 @xxx@ 原样残留，必命中此检查【REQ-ART-001】
  printf '%s\n' "$mods_toml" | grep -n '@' >&2
  fail "mods.toml 存在未渲染的 @token@ 残留（ReplaceTokens 失效？）"
fi
printf '%s\n' "$mods_toml" | grep -qF 'modId="queqiao_map_web"' \
  || fail 'mods.toml 缺少 modId="queqiao_map_web" 行'

# ---- 4. pack.mcmeta 合法 JSON ----------------------------------------------
# CI（ubuntu-latest）自带 jq；本地无 jq 时依次退化到 python3 / node，全都没有则跳过并警告
mcmeta="$(unzip -p "$jar" pack.mcmeta)"
json_ok=0
if command -v jq >/dev/null 2>&1; then
  printf '%s' "$mcmeta" | jq . >/dev/null 2>&1 && json_ok=1
elif command -v python3 >/dev/null 2>&1; then
  printf '%s' "$mcmeta" | python3 -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1 && json_ok=1
elif command -v node >/dev/null 2>&1; then
  printf '%s' "$mcmeta" | node -e 'let d="";process.stdin.on("data",c=>d+=c);process.stdin.on("end",()=>JSON.parse(d))' >/dev/null 2>&1 && json_ok=1
else
  echo "警告：jq/python3/node 均不可用，跳过 pack.mcmeta JSON 校验" >&2
  json_ok=1
fi
[ "$json_ok" = 1 ] || fail "pack.mcmeta 不是合法 JSON"

# ---- 5. 关键类已合入 jar（common 并进 + Forge 入口）【REQ-ART-002】 ----------
for cls in \
  net/yumezawa/queqiao_map_web/ModLifecycle.class \
  net/yumezawa/queqiao_map_web/forge/QueqiaoMapWeb.class
do
  printf '%s\n' "$listing" | grep -qxF "$cls" || fail "jar 缺少类文件 $cls"
done

# ---- 6. 绝不 shade 鹊桥（AGENT.md §2：运行期由鹊桥本体提供）【REQ-ART-002】 --
if printf '%s\n' "$listing" | grep -q '^com/github/theword/'; then
  printf '%s\n' "$listing" | grep '^com/github/theword/' >&2
  fail "jar 内含 com/github/theword/ 条目：违反「绝不 shade 鹊桥」"
fi

# ---- 7. MANIFEST 标注 mod id【REQ-ART-003】 ----------------------------------
if ! unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' \
     | grep -qF 'Minecraft-Mod-ID: queqiao_map_web'; then
  fail "MANIFEST.MF 缺少 Minecraft-Mod-ID: queqiao_map_web"
fi

echo "SMOKE OK $base"
