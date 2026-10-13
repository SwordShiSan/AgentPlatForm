#!/usr/bin/env bash
set -euo pipefail
umask 022
root=/srv/agentplatform
release=20261005.0
mode=${1:-dry-run}
case "$mode" in
 dry-run) echo "gVisor $release full package;4GiB XFS image; legacy pause before Docker restart";;
 download|verify)
  mkdir -p "$root/tools/gvisor-$release"
  cd "$root/tools/gvisor-$release"
  base="https://storage.googleapis.com/gvisor/releases/release/$release/x86_64"
  if [[ "$mode" == download ]]; then
   curl -fL --retry 3 --silent --show-error --connect-timeout 15 --max-time 600 -o gvisor.tar.bz2 "$base/gvisor.tar.bz2"
   curl -fL --retry 3 --silent --show-error --connect-timeout 15 --max-time 60 -o gvisor.tar.bz2.sha512 "$base/gvisor.tar.bz2.sha512"
  fi
  sha512sum --check gvisor.tar.bz2.sha512
  tar -tjf gvisor.tar.bz2 > contents.txt
  # Never extract absolute/traversal package names, even after checksum validation.
  if grep -E '(^/|(^|/)\.\.(/|$))' contents.txt; then exit 1; fi
  mkdir -p unpacked
  tar -xjf gvisor.tar.bz2 -C unpacked
  echo 'Pinned archive verified and unpacked; runtime not yet registered.'
  ;;
 install)
  test -f "$root/operations/legacy-paused"
  test -f "$root/tools/gvisor-$release/unpacked/runsc"
  if docker ps --format '{{.Names}}' | grep -Eq '^(ai-agent|big-market|mysql$|redis$|rabbitmq$|zookeeper$|nginx$)'; then exit 1; fi
  target="/usr/local/lib/agentplatform-gvisor/$release"
  mkdir -p "$target"
  cp -a "$root/tools/gvisor-$release/unpacked/." "$target/"
  chmod -R a+rX "$target"
  test -x "$target/runsc"
  "$target/runsc" --version
  command -v python3 >/dev/null
  mkdir -p /etc/docker
  if [[ -f /etc/docker/daemon.json && ! -e "$root/operations/docker-daemon.before-gvisor.json" ]]; then cp -p /etc/docker/daemon.json "$root/operations/docker-daemon.before-gvisor.json"; fi
  python3 - "$target/runsc" <<'PY'
import json,sys,pathlib
p=pathlib.Path('/etc/docker/daemon.json')
d=json.loads(p.read_text()) if p.exists() else {}
r=d.setdefault('runtimes',{})
entry={'path':sys.argv[1],'runtimeArgs':['--platform=systrap']}
if 'runsc' in r and r['runsc'] != entry: raise SystemExit('Existing runsc entry differs; refusing overwrite')
r['runsc']=entry
p.write_text(json.dumps(d,indent=2)+'\n')
PY
  dockerd --validate --config-file=/etc/docker/daemon.json
  systemctl restart docker
  docker info --format '{{json .Runtimes}}' > "$root/operations/docker-runtimes.json"
  ;;
 quota)
  mkdir -p "$root/quota" "$root/workspaces"
  image="$root/quota/workspaces.xfs"
  if [[ ! -e "$image" ]]; then
   available=$(df -B1 --output=avail "$root" | tail -1)
   ((available >= 12884901888)) || { echo 'Need12GiB free before4GiB preallocation' >&2; exit 1; }
   # Noclobber file creation; mkfs applies only to this brand-new image.
   (set -o noclobber; : > "$image")
   fallocate -l 4G "$image"
   mkfs.xfs "$image" > "$root/quota/mkfs-report.txt"
   blkid -s UUID -o value "$image" > "$root/quota/filesystem.uuid"
  fi
  test -s "$root/quota/filesystem.uuid"
  [[ $(blkid -s UUID -o value "$image") == $(cat "$root/quota/filesystem.uuid") ]]
  if ! mountpoint -q "$root/workspaces"; then
   [[ -z $(find "$root/workspaces" -mindepth 1 -maxdepth 1 -print -quit) ]] || { echo 'Underlay not empty' >&2; exit 1; }
   mount -o loop,prjquota,noatime "$image" "$root/workspaces"
  fi
  [[ $(findmnt -no FSTYPE --target "$root/workspaces") == xfs ]]
  findmnt -no OPTIONS --target "$root/workspaces" | grep -Eq '(^|,)prjquota(,|$)'
  [[ $(findmnt -no UUID --target "$root/workspaces") == $(cat "$root/quota/filesystem.uuid") ]]
  xfs_quota -x -c 'state -p' "$root/workspaces" > "$root/quota/quota-state.txt"
  cat > /etc/systemd/system/srv-agentplatform-workspaces.mount <<'EOF'
[Unit]
Description=Agent platform dedicated XFS image filesystem
After=local-fs.target
[Mount]
What=/srv/agentplatform/quota/workspaces.xfs
Where=/srv/agentplatform/workspaces
Type=xfs
Options=loop,prjquota,noatime
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable srv-agentplatform-workspaces.mount
  echo 'XFS mount identity and project quota state verified; per-project EDQUOT tests still required.'
  ;;
 *) echo 'Usage:sandbox-host.sh [dry-run|download|verify|install|quota]' >&2; exit 2;;
esac
