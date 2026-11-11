"""Prepared VPS inventory: explicit approval required, stdin only, redacted output.

Never import the application, read log bodies, send signals, or start/stop containers.
Full inspect/compose/proc data stays in process memory; only allowlisted summaries leave.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path('/opt/aura')
ACTIVE_IMAGE = 'sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2'
CANDIDATE_IMAGE = 'sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec'
FILES = ('infra/docker-compose.vps.yml', 'infra/docker-compose.vps.caddy.yml',
         'infra/security-lot1/compose.containment.yml', 'infra/security-lot1/compose.release.yml',
         '/root/aura-sec0024-staging/compose.candidate.yml')
EXPECTED_HASHES = dict(zip(FILES, (
    '58958f269c745489d1bb1efe2b9aa28dbfab28ac3e593f9af70034aa3ecd0e6a',
    '64ee1ad25bbff23501b515fefe6297d35d512322087414727e35b3431ca76a9a',
    '9822803dbb5375b9151fecff818c0bb5de541fc6f46782a1c5563d57450e17b3',
    'bb48cac36f47d943f3bbbe10b3c40bc0a529de5476e53d3dad863d4cc2d9a463',
    '75656ec54b52e3f17a8830c5bed900ee18d8e378f3c780f32ec88b752528b49b')))
CADDY_RELEASE_SHA = 'beb9c32353c174270adec49fb6840a437ad353e26de5a366598c99a194bc4586'
OLD_CMD = ['uvicorn', 'app.core.operational_logging:create_app', '--factory', '--host',
           '0.0.0.0', '--port', '8000', '--log-config', '/app/app/core/security-logging.json']
NEW_CMD = OLD_CMD[:3] + ['--workers', '1'] + OLD_CMD[3:]
FUTURE_OVERRIDE = json.dumps({'services': {'aura-api': {'image': CANDIDATE_IMAGE,
    'pull_policy': 'never', 'command': NEW_CMD}}})
ORIGIN = 'https://neosgkuccazvgkzwzjzh.supabase.co'
FLAGS = ('APP_DEBUG', 'ENABLE_STARTUP_RECOVERY', 'ENABLE_CACHE_BACKFILL')


def run(argv, *, body=None, env=None):
    result = subprocess.run(argv, input=body, env=env, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=120)
    if result.returncode:
        raise RuntimeError('Read-only command failed; details redacted')
    return result.stdout


def env_dict(values):
    return dict(value.split('=', 1) for value in values if '=' in value)


def rotation_safe(config):
    return (config.get('Type') == 'json-file' and
            config.get('Config', {}).get('max-size') == '10m' and
            config.get('Config', {}).get('max-file') == '3')


def service_summary(service, image, command):
    values = service.get('environment') or {}
    volumes = service.get('volumes') or []
    return dict(image_matches=service.get('image') == image,
        command_matches=service.get('command') == command,
        no_build=not service.get('build'), pull_never=service.get('pull_policy') == 'never',
        no_api_ports=not service.get('ports'),
        flags_safe=all(str(values.get(key)).lower() == 'false' for key in FLAGS),
        audio_only_mount=(len(volumes) == 1 and volumes[0].get('type') == 'volume'
                          and volumes[0].get('source') == 'aura-downloads'
                          and volumes[0].get('target') == '/app/downloads'),
        logging_safe=rotation_safe({'Type': (service.get('logging') or {}).get('driver'),
                                   'Config': (service.get('logging') or {}).get('options')}))


def container_summary(info):
    config, host = info['Config'], info['HostConfig']
    values = env_dict(config.get('Env') or [])
    mounts = info.get('Mounts') or []
    return dict(image=info['Image'], image_matches_lot2=info['Image'] == ACTIVE_IMAGE,
        running=info['State']['Running'], healthy=info['State'].get('Health', {}).get('Status') == 'healthy',
        flags_safe=all(values.get(key) == 'false' for key in FLAGS),
        origin_matches=values.get('SUPABASE_URL', '').rstrip('/') == ORIGIN,
        command_matches= config.get('Cmd') == OLD_CMD,
        concurrency_env_safe=values.get('WEB_CONCURRENCY', '1') == '1',
        audio_only_mount=(len(mounts) == 1 and mounts[0].get('Type') == 'volume'
                          and mounts[0].get('Name') == 'infra_aura-downloads'
                          and mounts[0].get('Destination') == '/app/downloads' and mounts[0].get('RW') is True),
        no_api_ports=not host.get('PortBindings'), no_host_network=host.get('NetworkMode') != 'host',
        no_host_pid_namespace=host.get('PidMode') != 'host',
        logging_safe=rotation_safe(host.get('LogConfig') or {}))


# Invoked INSIDE the existing API only to read /proc and configured path metadata.
# No app import, SQL, music/JSON contents or command arguments are returned.
PROCESS_PROBE = r'''
import json, os, subprocess
from pathlib import Path
counts=dict(python=0,uvicorn=0,gunicorn=0,ffmpeg=0,ffprobe=0,yt_dlp=0,deno=0,reloader=0)
for folder in Path('/proc').glob('[0-9]*'):
    if int(folder.name)==os.getpid(): continue
    try:
        args=(folder/'cmdline').read_bytes().split(b'\0')
        name=(folder/'comm').read_text().strip()
    except (OSError,ProcessLookupError): continue
    interpreter=Path(os.fsdecode(args[0])).name if args and args[0] else ''
    counts['python']+=int(name.startswith('python') or interpreter.startswith('python') or name=='uvicorn')
    counts['uvicorn']+=int(any(a==b'uvicorn' or a.endswith(b'/uvicorn') for a in args))
    counts['gunicorn']+=int(any(a==b'gunicorn' or a.endswith(b'/gunicorn') for a in args))
    counts['ffmpeg']+=int(name=='ffmpeg')
    counts['ffprobe']+=int(name=='ffprobe')
    counts['yt_dlp']+=int(name in ('yt-dlp','yt_dlp'))
    counts['deno']+=int(name=='deno')
    counts['reloader']+=int(b'--reload' in args)
root=Path(os.environ.get('DOWNLOADS_DIR','/app/downloads'))
sync=Path(os.environ.get('SYNC_FILES_DIR','sync_files'))
if not sync.is_absolute(): sync=Path.cwd()/sync
parts=sum(1 for p in root.iterdir() if p.is_file() and p.name.endswith(('.part','.ytdl')))
inside=sync==root or root in sync.parents
def size(path):
    if not path.exists(): return 0
    if path.is_symlink(): raise RuntimeError('Unsafe aggregate path')
    result=subprocess.run(['du','-x','-s','-B1',str(path)],capture_output=True,text=True,timeout=90)
    if result.returncode: raise RuntimeError('Aggregate size unavailable')
    return int(result.stdout.split()[0])
sizes=dict(attempt_workspaces=size(root/'_attempts'),legacy_trash=size(root/'_legacy_trash'))
if inside:
    sizes.update(sync=size(sync),global_cache=size(sync/'_global_cache'),
                 verified_cache=size(sync/'_global_cache'/'_native_v1'))
print(json.dumps(dict(process_counts=counts,partial_files=parts,
    sync_inside_audio=inside,allocated_bytes=sizes,native_threads_not_observable=True)))
'''


def storage(path):
    stat = os.statvfs(path)
    return dict(total_bytes=stat.f_blocks*stat.f_frsize, available_bytes=stat.f_bavail*stat.f_frsize,
                available_inodes=stat.f_favail)


def host_python_count():
    own_namespace = os.readlink('/proc/self/ns/pid')
    count = 0
    for folder in Path('/proc').glob('[0-9]*'):
        if int(folder.name) == os.getpid():
            continue
        try:
            if os.readlink(folder/'ns/pid') != own_namespace:
                continue
            name = (folder/'comm').read_text().strip()
            count += int(name.startswith('python') or name.startswith('gunicorn') or name.startswith('uvicorn'))
        except (FileNotFoundError, ProcessLookupError):
            continue
        except OSError:
            raise RuntimeError('Host process visibility incomplete') from None
    return count


def inventory():
    if os.environ.get('DOCKER_HOST', '') not in ('', 'unix:///var/run/docker.sock'):
        raise RuntimeError('Nonlocal Docker endpoint refused')
    endpoint = run(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}']).strip()
    if not endpoint.startswith('unix://'):
        raise RuntimeError('Nonlocal Docker context refused')
    def inspect(name):
        return json.loads(run(['docker', 'inspect', name]))[0]
    api, dev, caddy = [inspect(name) for name in ('aura-api', 'aura-api-dev', 'aura-caddy')]
    if not api['State']['Running'] or not caddy['State']['Running']:
        raise RuntimeError('Expected running service absent')
    hashes = {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in FILES}
    # Five files, including the override which removed the scripts bind in lot2.
    dc = ['docker', 'compose', '-p', 'infra']
    for name in FILES:
        dc += ['-f', str(ROOT/name)]
    environment = {**os.environ, 'AURA_SECURITY_IMAGE': ACTIVE_IMAGE, 'COMPOSE_PROFILES': ''}
    run(dc+['config', '--quiet'], env=environment)
    config = json.loads(run(dc+['config', '--format', 'json'], env=environment))
    profiled = json.loads(run(dc+['--profile', 'disabled-dev', 'config', '--format', 'json'], env=environment))
    future = json.loads(run(dc+['-f', '-', 'config', '--format', 'json'], body=FUTURE_OVERRIDE, env=environment))
    active = container_summary(api)
    # Docker Compose labels describe creation-time inputs; not sufficient alone.
    labels = api['Config'].get('Labels') or {}
    creation_files = labels.get('com.docker.compose.project.config_files', '').split(',')
    expected_files = [str(ROOT/name) for name in FILES]
    active['creation_files_match'] = creation_files == expected_files
    active['compose_project_matches'] = labels.get('com.docker.compose.project') == 'infra'
    volume = json.loads(run(['docker', 'volume', 'inspect', 'infra_aura-downloads']))[0]
    ids = run(['docker', 'ps', '-q', '--no-trunc']).split()
    all_running = json.loads(run(['docker', 'inspect', *ids])) if ids else []
    suspects = 0
    for info in all_running:
        if info['Id'] == api['Id']:
            continue
        values = env_dict(info['Config'].get('Env') or [])
        shared = any((m.get('Name') == 'infra_aura-downloads' or
                      m.get('Source', '').rstrip('/') == volume['Mountpoint'].rstrip('/'))
                     and m.get('RW') for m in info.get('Mounts') or [])
        same_origin = values.get('SUPABASE_URL', '').rstrip('/') == ORIGIN
        args = (info['Config'].get('Entrypoint') or []) + (info['Config'].get('Cmd') or [])
        if shared or same_origin or any('uvicorn' in part or 'gunicorn' in part for part in args):
            suspects += 1
    caddy_file = ROOT/'infra/caddy/Caddyfile'
    digest = hashlib.sha256(caddy_file.read_bytes()).hexdigest()
    adapted = json.loads(run(['docker', 'exec', 'aura-caddy', 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile']))
    live = json.loads(run(['docker', 'exec', 'aura-caddy', 'wget', '-qO-', 'http://127.0.0.1:2019/config/']))
    bind_digest = run(['docker', 'exec', 'aura-caddy', 'sha256sum', '/etc/caddy/Caddyfile']).split()[0]
    caddy_check = dict(running=caddy['State']['Running'],host_sha256=digest,
        bind_matches=bind_digest == digest, active_matches=adapted == live,
        matches_release=digest == CADDY_RELEASE_SHA == hashlib.sha256((ROOT/'infra/security-lot1/Caddyfile.release').read_bytes()).hexdigest(),
        logging_safe=rotation_safe(caddy['HostConfig'].get('LogConfig') or {}))
    processes = json.loads(run(['docker', 'exec', '-i', 'aura-api', 'python', '-B', '-'], body=PROCESS_PROBE))
    docker_root = run(['docker', 'info', '--format', '{{.DockerRootDir}}']).strip()
    # Private paths/names never leave: du is captured and only its byte count retained.
    size = int(run(['du', '-x', '-s', '-B1', volume['Mountpoint']]).split()[0])
    sockets = run(['ss', '-ltnH']).splitlines()
    exposed = any(line.split()[3].rsplit(':', 1)[-1] in ('8000', '8001') for line in sockets)
    services = config['services']
    result = dict(format='aura-sec0034-preflight-v1',utc_epoch=time.time(),remote_configuration_changed=False,
        compose_file_hashes=hashes,compose_hashes_match=hashes == EXPECTED_HASHES,active=active,
        current_compose=service_summary(services['aura-api'], ACTIVE_IMAGE, OLD_CMD),
        proposed_compose=service_summary(future['services']['aura-api'], CANDIDATE_IMAGE, NEW_CMD),
        dev=dict(stopped=not dev['State']['Running'] and dev['State']['Status'] == 'exited',
                 no_ports=not dev['HostConfig'].get('PortBindings'),
                 absent_default='aura-api-dev' not in services,
                 disabled_profile='disabled-dev' in profiled['services']['aura-api-dev'].get('profiles', [])),
        caddy=caddy_check,caddy_depends_prod_only=set(services['caddy'].get('depends_on', {})) == {'aura-api'},
        processes=processes,other_running_mutator_candidates=suspects,
        host_python_candidates=host_python_count(),host_api_sockets_absent=not exposed,
        storage=dict(staging=storage('/root'),docker=storage(docker_root),audio=storage(volume['Mountpoint']),
                     audio_allocated_bytes=size,du_deduplicates_hardlinks_within_volume=True),
        ntp_synchronized=run(['timedatectl','show','--property=NTPSynchronized','--value']).strip() == 'yes',
        external_or_administrative_writers_not_observable=True)
    return result


if __name__ == '__main__':
    if sys.argv[1:] != ['--execute-readonly']:
        print(json.dumps(dict(prepared_only=True,remote_accessed=False)))
        raise SystemExit(0)
    try:
        print(json.dumps(inventory(),sort_keys=True))
    except Exception:
        print(json.dumps(dict(preflight_complete=False,details_redacted=True)))
        raise SystemExit(1)
