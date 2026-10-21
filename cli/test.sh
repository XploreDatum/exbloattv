#!/usr/bin/env bash
#
# Offline checks for cli/exbloattv. No TV or emulator needed.
#   1. the embedded catalog and safety lists match the app (cli/gen-catalog.sh --check)
#   2. the script parses under every bash found (macOS /bin/bash is 3.2) and passes shellcheck (if installed)
#   3. unit tests, run under /bin/bash when it exists: JSON state file reading and editing (in the app's format),
#      SafetyGuard globs (the app's own test cases), and reading, grouping and planning the real Mi Box capture in
#      fixtures/mibox4-android9
#   4. cli/test-fixtures/state-from-cli.json is exactly what the script writes today (the app's Kotlin test
#      CliStateFixtureTest parses it, so the two can't drift). `cli/test.sh --update-fixture` rewrites it.

# The unit tests source the script and set the globals its functions read, which shellcheck can't see:
# shellcheck disable=SC1090,SC2034,SC2329,SC2016

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
script=$here/exbloattv

if [ "${1:-}" = --unit ]; then
	# --- unit tests (sourced functions) ------------------------------------------------------------------------
	EXBLOATTV_LIB=1 . "$script"
	setup_style
	load_safety
	TMPD=$(mktemp -d "${TMPDIR:-/tmp}/exbloattv-test.XXXXXX")
	trap 'rm -rf "$TMPD"' EXIT
	fails=0 count=0
	eq() { # eq NAME ACTUAL EXPECTED
		count=$((count + 1))
		if [ "$2" != "$3" ]; then
			fails=$((fails + 1))
			printf 'FAIL %s\n  expected: %s\n  actual:   %s\n' "$1" "$3" "$2"
		fi
	}
	yes_() { if "$@"; then echo yes; else echo no; fi; }
	now_ms() { echo 1700000000000; }
	rand_hex() { echo 0000abcd; }
	ME='test-host (exbloattv CLI) · 0000'

	# JSON strings
	eq json_str "$(json_str 'a"b\c	d')" '"a\"b\\c d"'

	# A state file in the app's format (kotlinx, compact, encodeDefaults) with an unknown future field.
	TV_SERIAL=SER1
	app='{"version":1,"serial":"SER1","original":{"takenAt":1,"byPhone":"Pixel 8 · 1a2b","enabled":["a"],"disabled":["b"],"userInstalled":["c"],"resolvedHome":"home","animations":{"window":1.0,"transition":1.0,"animator":1.0},"backgroundOps":{"p":"allow"}},"latest":null,"changes":[{"id":"run-1-aa:1:bb","kind":"DISABLE","pkg":"pkg.one","from":"enabled","to":"disabled","byPhone":"Pixel \"8\"","at":5,"verified":true},{"id":"x2","kind":"ANIMATIONS","pkg":null,"from":"1.0,null,1.0","to":"0.5,0.5,0.5","byPhone":"P","at":6,"verified":false}],"lock":{"byPhone":"Pixel 8","at":9},"extra":{"x":[1,2]}}'
	ST_RAW=$app
	state_parse
	eq parse.status "$ST_STATUS" ok
	eq parse.lock "$ST_LOCK_BY@$ST_LOCK_AT" "Pixel 8@9"
	eq parse.ids "${CH_ID[*]}" "run-1-aa:1:bb x2"
	eq parse.kinds "${CH_KIND[*]}" "DISABLE ANIMATIONS"
	eq parse.nullpkg "${CH_PKG[1]}" ""
	eq parse.escaped "${CH_BY[0]}" 'Pixel "8"'
	eq parse.verified "${CH_VER[*]}" "true false"
	EXB_LOCK='' state_edit
	eq edit.noop-is-identical "$NEW_RAW" "$app"
	EXB_LOCK=null EXB_DROP=x2 EXB_UPSERT="$(change_json new1 ENABLE pkg.two disabled enabled false 77)
$(change_json run-1-aa:1:bb DISABLE pkg.one enabled disabled true 5)" state_edit
	ST_RAW=$NEW_RAW
	state_parse
	eq edit.ids "${CH_ID[*]}" "run-1-aa:1:bb new1"
	eq edit.lock "$ST_LOCK_BY" ""
	eq edit.keeps-restore-point "$(printf '%s' "$NEW_RAW" | json_run flat | grep -c '^original\.')" 10
	eq edit.keeps-unknown "$(printf '%s' "$NEW_RAW" | json_run flat | grep '^extra')" "$(printf 'extra.x.0\t1\nextra.x.1\t2')"
	for bad in '{"version":2,"serial":"SER1"}' '{"version":"1","serial":"SER1"}' \
		'{"serial":"SER1","changes":[{"id":"a","kind":"NEWKIND","pkg":null,"from":"","to":"","byPhone":"","at":1,"verified":true}]}' \
		'{"serial":"SER1","changes":[{"id":"a","kind":"DISABLE","pkg":"p.q","from":"","to":"","byPhone":"","at":"1","verified":true}]}' \
		'{"serial":"SER1","lock":"held"}'; do
		ST_RAW=$bad
		state_parse
		eq "toonew $bad" "$ST_STATUS" toonew
	done
	for bad in 'garbage' '' '[1]' '{"serial":1}' '{"version":1}'; do
		ST_RAW=$bad
		state_parse
		eq "notours $bad" "$ST_STATUS" notours
	done
	ST_RAW='{"serial":"OTHER"}'
	state_parse
	eq othertv "$ST_STATUS" othertv
	ST_RAW='{"serial":"SER1"}' # no version, no changes: a version 1 file (Kotlin defaults)
	state_parse
	eq minimal "$ST_STATUS/$NCH" ok/0
	EXB_UPSERT="$(change_json a1 DISABLE p.q enabled disabled false 1)" state_edit
	eq edit.adds-missing-keys "$NEW_RAW" '{"serial":"SER1","changes":[{"id":"a1","kind":"DISABLE","pkg":"p.q","from":"enabled","to":"disabled","byPhone":"test-host (exbloattv CLI) · 0000","at":1,"verified":false}],"lock":null}'

	# The editor refuses to write anything malformed, and a parser that didn't finish never reads as "not ours".
	ST_RAW='{"serial":"SER1","changes":[],"lock":null}'
	eq refuse.truncated-change "$(EXB_UPSERT='{"id":"a1","kind":"DISABLE","pkg":"p.q","fr' state_edit && echo wrote || echo refused)" refused
	eq refuse.bad-lock "$(EXB_LOCK='{"byPhone":"x"' state_edit && echo wrote || echo refused)" refused
	eq refuse.bad-point "$(EXB_LATEST='{"takenAt":1' state_edit && echo wrote || echo refused)" refused
	eq accept.good-change "$(EXB_UPSERT="$(change_json a1 DISABLE p.q enabled disabled false 1)" state_edit && echo wrote || echo refused)" wrote
	json_run() { return 143; } # as if Ctrl-C killed awk
	ST_RAW='{"serial":"SER1"}'
	eq killed-parser "$(state_parse && echo "parsed:$ST_STATUS" || echo failed)" failed
	unset -f json_run
	json_run() { LC_ALL=C EXB_MODE=$1 awk "$JSON_AWK"; }

	# Files the script wrote on the emulator parse back.
	TV_SERIAL=EMULATOR36X5X11X0
	ST_RAW=$(cat "$here/test-fixtures/state-emulator-run.json")
	state_parse
	eq emulator-run "$ST_STATUS ${CH_KIND[*]}" "ok DISABLE DISABLE DISABLE ANIMATIONS"
	ST_RAW=$(cat "$here/test-fixtures/state-emulator-swap.json")
	state_parse
	latest_swap
	eq emulator-swap "$ST_STATUS ${CH_KIND[*]} $LAST_DONE" "ok ANIMATIONS INSTALL_APK DISABLE LAUNCHER_SWAP 1"

	# SafetyGuard, with the app's own test cases (SafetyGuardTest).
	for p in android com.android.systemui com.android.packageinstaller com.google.android.packageinstaller com.android.shell \
		com.android.bluetooth com.android.se com.android.keychain com.android.certinstaller com.android.vending \
		com.google.android.gms com.google.android.gsf com.google.android.webview com.google.android.permissioncontroller \
		com.google.android.tv.remote.service mitv.service com.xiaomi.mitv.res com.android.dreams.basic com.android.settings \
		com.android.settings.intelligence com.android.tv.settings com.android.tv.settings.x com.android.providers.media.module \
		com.android.providers.settings com.android.networkstack.tethering com.android.wifi.resources com.google.android.ext.services \
		com.google.android.ext.shared com.google.android.inputmethod.latin com.google.android.tv.inputmethod.x com.foo.globalkeyoverlay \
		com.foo.autopair com.tcl.tvinput com.foo.systemserver com.droidlogic.ble com.droidlogic com.droidlogic.x com.amlogic.tvservice \
		com.mediatek.x com.realtek.y com.android.tethering com.android.tethering.x com.google.android.modulemetadata \
		com.google.android.overlay.x com.foo.providers.config com.tvos com.android.settingsx; do
		eq "protected $p" "$(yes_ safety_protected "$p")" yes
	done
	for p in com.google.android.videos com.google.android.feedback com.google.android.partnersetup com.android.printspooler \
		com.android.camera2 com.mitv.tvhome.atv tv.alphonso.alphonso_eula com.netflix.ninja com.google.android.youtube.tv \
		com.spotify.tv.android com.google.android.katniss com.google.android.tungsten.setupwraith xandroid androidx \
		com.android.systemuix comXandroid.systemui com.android.setting com.droidlogi ''; do
		eq "not protected $p" "$(yes_ safety_protected "$p")" no
	done

	# The Mi Box 4 capture: read, group and plan it like a real TV.
	fx=$root/fixtures/mibox4-android9
	d=$TMPD/inv
	mkdir -p "$d"
	sed -n 's/^package:.*\.apk=\([^ ]*\).*/\1/p' "$fx/pm_list_all.txt" | sort -u >"$d/all.list"
	sed -n 's/^package://p' "$fx/pm_list_system.txt" | tr -d '\r' | sort -u >"$d/sys.list"
	sed -n 's/^package://p' "$fx/pm_list_disabled.txt" | tr -d '\r' | sort -u >"$d/dis.list"
	: >"$d/removed.list"
	{ home_pkgs_from "$(cat "$fx/query_leanback.txt")"; home_pkgs_from "$(cat "$fx/query_launcher.txt")"; } | sort -u >"$d/launchable.list"
	home_pkgs_from "$(cat "$fx/query_home.txt")" >"$d/home.list"
	printf 'com.netflix.ninja\tignore\n' >"$d/bgops"
	TV_HOME=$(resolved_from "$(cat "$fx/resolve_home.txt")")
	eq resolved-home "$TV_HOME" "me.efesser.flauncher/.MainActivity" # captured after a cleanup
	ACTIVE_LAUNCHER=${TV_HOME%%/*} TV_MANUFACTURER=Xiaomi TV_SDK=28
	categorize
	eq mibox.count "$N" "$(wc -l <"$d/all.list" | tr -d ' ')"
	row() { # row PKG: "category|name|keep|locked"
		local i
		for ((i = 0; i < N; i++)); do [ "${A_PKG[i]}" = "$1" ] && printf '%s|%s|%s|%s' "${A_CAT[i]}" "${A_NAME[i]}" "${KEEP[i]}" "${A_LOCK[i]}" && return; done
		printf 'missing'
	}
	eq mibox.netflix "$(row com.netflix.ninja)" "STREAMING|Netflix|1|0"
	eq mibox.alphonso "$(row tv.alphonso.alphonso_eula)" "ADS_TRACKING|$(catalog_tsv | awk -F'\037' '$1 == "tv.alphonso.alphonso_eula" {print $3}')|0|1"
	eq mibox.remote-is-system "$(row com.google.android.tv.remote.service | cut -d'|' -f1,4)" "SYSTEM|1"
	eq mibox.launcher "$(row com.google.android.tvlauncher | cut -d'|' -f1)" "LAUNCHER"
	eq mibox.mitvservice "$(row mitv.service | cut -d'|' -f1)" "SYSTEM"
	eq mibox.sorted "$(cut -d "$US" -f2 "$TMPD/apps" | awk '{c = $0; if (c != last) {printf "%s ", c; last = c}}')" \
		"$(cut -d "$US" -f2 "$TMPD/apps" | awk '!s[$0]++ {printf "%s ", $0}')"
	ANIM_RAW=(1.0 1.0 1.0) OPT_ANIM=1 OPT_BG=1 OPT_TRIM=1 OPT_HOME=1 OPT_REMOVE=0
	plan_build
	planned=''
	for s in "${STEPS[@]}"; do
		case $s in disable* | remove*) planned="$planned ${A_PKG[${s#* }]}" ;; esac
	done
	for p in $planned; do
		eq "never plans protected $p" "$(yes_ safety_protected "$p")" no
		[ "$p" = "$ACTIVE_LAUNCHER" ] && eq "never plans the home screen" "$p" "-"
	done
	in_list tv.alphonso.alphonso_eula "$planned" || grep -qx tv.alphonso.alphonso_eula "$d/dis.list" || eq mibox.ads-off missing planned
	eq mibox.tail "${STEPS[*]: -3}" "animations trim reboot" # FLauncher is already home: no swap
	bg_pkgs() { local s; for s in "${STEPS[@]}"; do [ "${s%% *}" = background ] && printf '%s ' "${A_PKG[${s#* }]}"; done; }
	eq mibox.background-skips-ignored "$(yes_ in_list com.netflix.ninja "$(bg_pkgs)")" no
	TV_HOME=com.google.android.tvlauncher/.MainActivity ACTIVE_LAUNCHER=com.google.android.tvlauncher
	plan_build
	eq mibox.swap-when-stock-home "${STEPS[*]: -4}" "animations trim swap reboot"
	OPT_REMOVE=1
	plan_build
	removed_user() { local s; for s in "${STEPS[@]}"; do [ "${s%% *}" = remove ] && [ "${A_SYS[${s#* }]}" != 1 ] && echo user; done; }
	eq mibox.remove-only-system "$(removed_user)" ""
	eq mibox.remove-planned "$(yes_ in_list remove "${STEPS[*]}")" yes
	TV_MANUFACTURER=Amazon OPT_REMOVE=0
	plan_build
	eq firetv.no-swap "$(yes_ in_list swap "${STEPS[*]}")" no

	# Small parsers on the capture.
	TV_OUT=$(cat "$fx/appops_netflix.txt")
	eq appop "$(printf '%s\n' "$TV_OUT" | awk -v op=RUN_ANY_IN_BACKGROUND '{ line = $0; sub(/^[ \t]*/, "", line); if (index(line, op ":") == 1) { line = substr(line, length(op) + 2); sub(/^[ \t]*/, "", line); if (match(line, /^[A-Za-z0-9_]+/)) { print substr(line, 1, RLENGTH); exit } } }')" ignore
	tv_read() { TV_OUT=$(cat "$fx/resumed_activity.txt") TV_RC=0; }
	tv_sh() { TV_OUT=$(cat "$fx/resumed_activity.txt") TV_RC=0; }
	foreground
	eq foreground "$FG" "$(sed -n 's/.*ActivityRecord{[^}]* u[0-9]* \([^/ ]*\)\/.*/\1/p' "$fx/resumed_activity.txt" | head -n 1)"

	# The fixture the app's Kotlin test parses: every change kind, a lock and restore points, as the script writes them.
	TV_SERIAL=CLI-FIXTURE-1 TV_HOME=com.google.android.tvlauncher/.MainActivity
	A_PKG=(com.netflix.ninja com.google.android.feedback com.example.removed) A_EN=(1 0 0) A_SYS=(0 1 1) A_REM=(0 0 1)
	ANIM_RAW=(1.0 null 1.0)
	printf 'com.netflix.ninja\tallow\n' >"$TMPD/inv/bgops"
	rp=$(restore_point_json)
	ST_RAW=$(fresh_state)
	EXB_ORIGINAL=$rp EXB_LATEST=$rp EXB_LOCK=$(lock_json) EXB_UPSERT="$(change_json run-1700000000000-0000abcd:1:0000abcd DISABLE com.google.android.feedback enabled disabled true 1700000000000)
$(change_json run-1700000000000-0000abcd:2:0000abcd REMOVE_FOR_USER com.example.removed enabled removed true 1700000001000)
$(change_json run-1700000000000-0000abcd:3:0000abcd ENABLE com.example.on disabled enabled true 1700000002000)
$(change_json run-1700000000000-0000abcd:4:0000abcd INSTALL_EXISTING com.example.back removed installed true 1700000003000)
$(change_json run-1700000000000-0000abcd:5:RUN_ANY_IN_BACKGROUND:0000abcd APPOPS com.netflix.ninja RUN_ANY_IN_BACKGROUND=allow RUN_ANY_IN_BACKGROUND=ignore true 1700000004000)
$(change_json run-1700000000000-0000abcd:6:0000abcd ANIMATIONS '' '1.0,null,1.0' '0.5,0.5,0.5' false 1700000005000)
$(change_json run-1700000000000-0000abcd:7:0000abcd UNINSTALL com.example.gone installed deleted true 1700000006000)
$(change_json swap-1700000007000-0000abcd:install INSTALL_APK me.efesser.flauncher 'not installed' 0.18.0 true 1700000007000)
$(change_json swap-1700000007000-0000abcd:disable:com.google.android.tvlauncher DISABLE com.google.android.tvlauncher enabled disabled true 1700000008000)
$(change_json swap-1700000007000-0000abcd:home LAUNCHER_SWAP me.efesser.flauncher com.google.android.tvlauncher me.efesser.flauncher true 1700000009000)" state_edit
	if [ "${UPDATE_FIXTURE:-}" = 1 ]; then
		printf '%s\n' "$NEW_RAW" >"$here/test-fixtures/state-from-cli.json"
		echo "wrote cli/test-fixtures/state-from-cli.json"
	fi
	eq fixture.state-from-cli "$NEW_RAW" "$(cat "$here/test-fixtures/state-from-cli.json")"
	ST_RAW=$NEW_RAW
	state_parse
	eq fixture.parses "$ST_STATUS $NCH $ST_LOCK_BY" "ok 10 $ME"

	echo "unit tests: $((count - fails)) of $count passed ($BASH_VERSION)"
	[ "$fails" = 0 ]
	exit
fi

# --- the suite ---------------------------------------------------------------------------------------------------
status=0
step() { printf '\n== %s\n' "$1"; }

step "embedded catalog and SafetyGuard lists match the app"
"$here/gen-catalog.sh" --check || status=1

step "syntax"
seen=''
for b in /bin/bash "$(command -v bash)"; do
	[ -x "$b" ] || continue
	case " $seen " in *" $b "*) continue ;; esac
	seen="$seen $b"
	if "$b" -n "$script" && "$b" -n "$here/gen-catalog.sh" && "$b" -n "$0"; then
		echo "ok: $("$b" -c 'echo bash $BASH_VERSION') ($b)"
	else
		status=1
	fi
done

step "shellcheck"
if command -v shellcheck >/dev/null 2>&1; then
	if shellcheck -s bash "$script" "$here/gen-catalog.sh" "$0"; then echo "ok: no warnings"; else status=1; fi
else
	echo "skipped: shellcheck isn't installed (brew install shellcheck / apt install shellcheck)"
fi

step "unit tests"
ub=/bin/bash
[ -x "$ub" ] || ub=$(command -v bash)
if [ "${1:-}" = --update-fixture ]; then
	UPDATE_FIXTURE=1 "$ub" "$0" --unit || status=1
else
	"$ub" "$0" --unit || status=1
fi

printf '\n'
if [ "$status" = 0 ]; then echo "all checks passed"; else echo "SOME CHECKS FAILED"; fi
exit "$status"
