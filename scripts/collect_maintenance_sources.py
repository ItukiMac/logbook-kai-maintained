#!/usr/bin/env python3
"""Bounded allowlisted public-source retrieval to a NEW evidence directory.

Explicit one-shot use only. No cookies, credentials, redirect following, paid API,
LLM call, rule inference, approval, source modification or publication.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
from http.client import HTTPException
import json
from pathlib import Path
from urllib.error import URLError
from urllib.parse import urlsplit, quote, urlunsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
from maintenance_plan import source_allowed
from build_feature_distribution import new_directory

MAX_BYTES = 2 * 1024 * 1024
MAX_REQUESTS = 4


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def canonical_url(url):
    if not isinstance(url, str) or not url or any(ord(c) < 33 for c in url):
        raise ValueError('Invalid URL text')
    if not source_allowed(url):
        raise ValueError('Source URL is not allowlisted')
    p = urlsplit(url)
    # No arbitrary query parameters/fragment, potential tokens or redirectors.
    if p.query or p.fragment:
        raise ValueError('Query strings/fragments are not source evidence URLs')
    return urlunsplit(('https', p.netloc, quote(p.path, safe='/%'), '', ''))


def collect(urls, output: Path, *, open_url=None):
    if not isinstance(urls, list) or not 1 <= len(urls) <= MAX_REQUESTS:
        raise ValueError('One to four sources required')
    # Reject all unsafe requests before opening network connections or output.
    normalized = [canonical_url(url) for url in urls]
    if len(set(normalized)) != len(normalized):
        raise ValueError('Duplicate source URL')
    if open_url is None:
        open_url = build_opener(ProxyHandler({}), NoRedirect()).open
    new_directory(output)
    rows = []
    for index, url in enumerate(normalized):
        row = {'url': url, 'checked_at': datetime.now(timezone.utc).isoformat()}
        try:
            request = Request(url, headers={'User-Agent': 'logbook-maintenance-evidence/1.0',
                                           'Accept': 'text/html,text/plain', 'Accept-Encoding': 'identity'})
            with open_url(request, timeout=10) as response:
                if response.geturl() != url or response.status != 200:
                    raise ValueError('Redirected or unsuccessful response')
                media = response.headers.get_content_type()
                if media not in ('text/html', 'text/plain', 'application/xhtml+xml'):
                    raise ValueError('Unsupported source media type')
                if response.headers.get('Content-Encoding', 'identity').lower() != 'identity':
                    raise ValueError('Unexpected content encoding')
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES or not raw:
                    raise ValueError('Empty or oversized evidence')
                name = f'source-{index:02d}.bin'
                with (output / name).open('xb') as file:
                    file.write(raw)
                row.update(status='captured', file=name, bytes=len(raw), media_type=media,
                           evidence_sha256=hashlib.sha256(raw).hexdigest())
        except (URLError, TimeoutError, OSError, ValueError, HTTPException) as error:
            # Do not emit HTTP response bodies, request credentials or private URLs.
            row.update(status='error', error=type(error).__name__)
        rows.append(row)
    report = {'format': 'logbook-public-evidence-v1', 'entries': rows,
              'requests': len(rows), 'max_response_bytes': MAX_BYTES,
              'complete': all(r['status'] == 'captured' for r in rows),
              'semantic_reviewed': False, 'approved': False,
              'provider_calls': 0, 'paid_api_calls': 0,
              'cost_status': 'No paid-provider API invoked; hosting/network costs not measured.'}
    with (output / 'evidence.json').open('x', encoding='utf8') as file:
        json.dump(report, file, ensure_ascii=False, indent=2)
        file.write('\n')
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--url', action='append', required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    result = collect(a.url, a.out)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['complete'] else 2)

if __name__ == '__main__':
    main()
