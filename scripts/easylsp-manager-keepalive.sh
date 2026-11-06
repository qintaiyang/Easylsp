#!/system/bin/sh
# UID 0 service; protect running managers without starting their Activities.
state_dir=/data/adb/easylsp-manager-keepalive
service=/data/adb/service.d/easylsp-manager-keepalive.sh
[ "$(id -u)" = 0 ] || exit 1
mkdir -p "$state_dir/processes"

keeper_pid() {
    [ -f "$state_dir/pid" ] || return 1
    read -r keeper < "$state_dir/pid"
    case "$keeper" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$keeper/cmdline" ] || return 1
    case "$(tr '\000' ' ' < "/proc/$keeper/cmdline")" in *"$service run "*) printf '%s' "$keeper";; *) return 1;; esac
}
restore_processes() {
    for saved in "$state_dir"/processes/*; do
        [ -d "$saved" ] || continue
        pid=${saved##*/}
        if [ -r "/proc/$pid/stat" ] && [ "$(awk '{print $22}' "/proc/$pid/stat")" = "$(cat "$saved/start")" ]; then
            cat "$saved/oom" > "/proc/$pid/oom_score_adj"
            while IFS=: read -r hierarchy controllers relative; do
                case "$controllers" in
                    '') group="/sys/fs/cgroup$relative";;
                    freezer) group="/dev/freezer$relative";;
                    *) continue;;
                esac
                if [ -f "$group/cgroup.procs" ]; then echo "$pid" > "$group/cgroup.procs"; fi
            done < "$saved/cgroup"
        fi
        rm -rf "$saved"
    done
}
status() {
    enabled=false; [ ! -f "$state_dir/enabled" ] || enabled=true
    if keeper=$(keeper_pid); then
        printf '{"enabled":%s,"running":true,"keeperUid":0,"keeperPid":%s,"managers":' "$enabled" "$keeper"
        if [ -f "$state_dir/managers.json" ]; then cat "$state_dir/managers.json"; else printf '[]'; fi
        printf '}\n'
    else
        printf '{"enabled":%s,"running":false,"managers":[]}\n' "$enabled"
    fi
}
case "${1:-start}" in
    status) status; exit;;
    stop)
        if keeper=$(keeper_pid); then
            kill -TERM "$keeper"
            # Wait for the trap to restore only the processes this keeper changed.
            while keeper_pid >/dev/null; do sleep .1; done
        else
            restore_processes
        fi
        exit;;
    start)
        [ -f "$state_dir/enabled" ] || exit 0
        keeper_pid >/dev/null && exit 0
        rm -f "$state_dir/managers.json"
        nohup /system/bin/sh "$service" run </dev/null >"$state_dir/service.log" 2>&1 &
        while [ ! -f "$state_dir/managers.json" ]; do sleep .1; done
        exit;;
    run) ;;
    *) exit 2;;
esac

# The service also leaves the manager's cgroup when started from an app su call.
[ ! -f /sys/fs/cgroup/cgroup.procs ] || echo $$ > /sys/fs/cgroup/cgroup.procs
[ ! -f /dev/freezer/cgroup.procs ] || echo $$ > /dev/freezer/cgroup.procs
trap 'restore_processes; rm -f "$state_dir/pid" "$state_dir/managers.json"; exit 0' TERM INT
boot=$(cat /proc/sys/kernel/random/boot_id)
if [ -f "$state_dir/boot" ] && [ "$(cat "$state_dir/boot")" != "$boot" ]; then rm -rf "$state_dir"/processes/*; fi
printf '%s' "$boot" > "$state_dir/boot"
restore_processes
echo $$ > "$state_dir/pid"
while :; do
    managers='['; separator=
    for pid in $(pidof io.github.easylsp); do
        [ -r "/proc/$pid/stat" ] || continue
        saved="$state_dir/processes/$pid"
        if [ ! -d "$saved" ]; then
            mkdir -p "$saved"
            awk '{print $22}' "/proc/$pid/stat" > "$saved/start"
            cat "/proc/$pid/oom_score_adj" > "$saved/oom"
            cat "/proc/$pid/cgroup" > "$saved/cgroup"
        fi
        uid=$(awk '/^Uid:/ {print $2}' "/proc/$pid/status")
        [ -n "$uid" ] || continue
        # Match AOSP unfreeze order: thaw Binder before allowing CPU execution.
        unfreeze=$("$state_dir/unfreeze-uid" "$uid" "$pid") || { [ -n "$unfreeze" ] || continue; }
        while IFS=: read -r hierarchy controllers relative; do
            case "$controllers" in
                '')
                    freeze="/sys/fs/cgroup$relative/cgroup.freeze"
                    [ ! -f "$freeze" ] || echo 0 > "$freeze"
                    echo "$pid" > /sys/fs/cgroup/cgroup.procs;;
                freezer)
                    freeze="/dev/freezer$relative/freezer.state"
                    [ ! -f "$freeze" ] || echo THAWED > "$freeze"
                    echo "$pid" > /dev/freezer/cgroup.procs;;
            esac
        done < "/proc/$pid/cgroup"
        echo -1000 > "/proc/$pid/oom_score_adj"
        oom=$(cat "/proc/$pid/oom_score_adj")
        [ -n "$uid" ] && [ -n "$oom" ] || continue
        cgroup=$(awk -F: '$1 == "0" || $2 == "freezer" {print $3; exit}' "/proc/$pid/cgroup")
        managers="$managers$separator{\"pid\":$pid,\"uid\":$uid,\"oomScoreAdj\":$oom,\"cgroup\":\"$cgroup\",\"unfreeze\":$unfreeze}"
        separator=,
    done
    printf '%s]\n' "$managers" > "$state_dir/managers.tmp"
    mv "$state_dir/managers.tmp" "$state_dir/managers.json"
    sleep 2 & wait $!
done
