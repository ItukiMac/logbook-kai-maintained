#!/usr/bin/env python3
"""Create a new Linux kai2 store without touching the legacy application."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import socket
import sys
import feature_bundle_manager as manager
from run_feature_distribution import verify


def port_free(port):
    if not 1024 <= port <= 65535:
        raise ValueError('Port must be 1024..65535')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))


def prepare(bundle, store, bridge_port=8892, proxy_port=8889, java='java'):
    if sys.platform != 'linux':
        raise ValueError('This test launcher targets Linux')
    if len({bridge_port, proxy_port, 8891}) != 3:
        raise ValueError('Ports must differ from each other and legacy bridge 8891')
    port_free(bridge_port); port_free(proxy_port)
    bundle=Path(bundle).absolute();store=Path(store).absolute()
    verify(bundle)
    digest=hashlib.sha256((bundle/'distribution.json').read_bytes()).hexdigest()
    manager.initialize(store)
    manager.stage(store,bundle,digest)
    manager.activate(store,digest,None)
    command=[sys.executable,str(Path(__file__).with_name('feature_bundle_manager.py').absolute()),
             '--store',str(store),'launch','--java',java,'--bridge-port',str(bridge_port),'--proxy-port',str(proxy_port)]
    launcher=store/'launch-kai2.sh'
    launcher.write_text('#!/bin/sh\nset -eu\nexec '+shlex.join(command)+'\n')
    launcher.chmod(0o700)
    report={'store':str(store),'bridge_port':bridge_port,'proxy_port':proxy_port,
            'legacy_bridge_port':8891,'manifest_sha256':digest,'launched':False,
            'legacy_modified':False,'launcher':str(launcher)}
    (store/'parallel-profile.json').write_text(json.dumps(report,indent=2)+'\n')
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bundle',type=Path,required=True);p.add_argument('--store',type=Path,required=True)
    p.add_argument('--bridge-port',type=int,default=8892);p.add_argument('--proxy-port',type=int,default=8889)
    p.add_argument('--java',default='java');a=p.parse_args()
    print(json.dumps(prepare(a.bundle,a.store,a.bridge_port,a.proxy_port,a.java),indent=2))

if __name__=='__main__':main()
