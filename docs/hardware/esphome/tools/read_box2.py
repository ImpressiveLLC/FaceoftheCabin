#!/usr/bin/env python3
"""Read an ESPHome sensor node (Box 2) over its encrypted native API. Read-only.

  python read_box2.py read                          # one snapshot, printed
  python read_box2.py log --duration 600 --out box2_log.csv   # append CSV rows

The API encryption key is NEVER stored in the repo. Provide it one of two ways:
  BOX2_API_KEY=<base64 key>            (environment variable), or
  --secrets <path to an ESPHome secrets.yaml containing an api_key: line>
The key is never printed. Host defaults to $BOX2_HOST, else 192.168.1.130.

Needs: pip install "aioesphomeapi==46.12.0"   (the version Box 2 was built with)
Connecting as an API client also makes the node's status light go rainbow while connected.
"""
import argparse, asyncio, csv, os, re, sys, time

try:
    import aioesphomeapi
except ImportError:
    sys.exit('aioesphomeapi missing: pip install "aioesphomeapi==46.12.0"')

DEFAULT_HOST = '192.168.1.130'


def load_key(secrets_path):
    key = os.environ.get('BOX2_API_KEY')
    if key:
        return key.strip()
    if secrets_path:
        m = re.search(r'^api_key:\s*"?([^"\s]+)"?', open(secrets_path, encoding='utf-8').read(), re.M)
        if m:
            return m.group(1)
    sys.exit('no API key: set BOX2_API_KEY or pass --secrets <secrets.yaml with api_key:>')


async def connect(host, key):
    client = aioesphomeapi.APIClient(host, 6053, None, noise_psk=key)
    await client.connect(login=True)
    entities, _ = await client.list_entities_services()
    return client, {e.key: e for e in entities}


def value(state):
    s = getattr(state, 'state', None)
    return s if isinstance(s, (float, int, bool, str)) else None


async def do_read(host, key, wait):
    client, ents = await connect(host, key)
    info = await client.device_info()
    got = {}
    client.subscribe_states(lambda s: got.__setitem__(s.key, value(s)) if s.key in ents else None)
    for _ in range(wait):
        await asyncio.sleep(1)
        if len(got) >= len(ents):
            break
    print(f'{info.name}  {host}  mac={info.mac_address}  esphome={info.esphome_version}')
    for k, e in ents.items():
        unit = getattr(e, 'unit_of_measurement', '') or ''
        v = got.get(k, '(no state yet)')
        if isinstance(v, float):
            v = round(v, 2)
        print(f'  {e.name:38} {v} {unit}')
    await client.disconnect()


async def do_log(host, key, duration, out):
    client, ents = await connect(host, key)
    new = not os.path.exists(out) or os.path.getsize(out) == 0
    f = open(out, 'a', newline='', encoding='utf-8')
    w = csv.writer(f)
    if new:
        w.writerow(['t_s', 'iso', 'name', 'value'])
    t0 = time.time()

    def cb(s):
        v = value(s)
        if s.key in ents and isinstance(v, (float, int)) and not isinstance(v, bool):
            w.writerow([round(time.time() - t0, 1), time.strftime('%Y-%m-%dT%H:%M:%S'), ents[s.key].name, round(float(v), 3)])
            f.flush()

    client.subscribe_states(cb)
    await asyncio.sleep(duration)
    f.close()
    await client.disconnect()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('mode', choices=['read', 'log'])
    p.add_argument('--host', default=os.environ.get('BOX2_HOST', DEFAULT_HOST))
    p.add_argument('--secrets')
    p.add_argument('--duration', type=int, default=60, help='log mode: seconds to record')
    p.add_argument('--out', default='box2_log.csv', help='log mode: CSV file (appended)')
    p.add_argument('--wait', type=int, default=40, help='read mode: max seconds to wait for all states')
    a = p.parse_args()
    key = load_key(a.secrets)
    if a.mode == 'read':
        asyncio.run(do_read(a.host, key, a.wait))
    else:
        asyncio.run(do_log(a.host, key, a.duration, a.out))


if __name__ == '__main__':
    main()
