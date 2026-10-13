"""Real browser UI smoke with deterministic HTTP fixtures; profile and artifacts stay on E:."""
from pathlib import Path
import json
import sys
import uuid

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / '.build/python-tools'))
from playwright.sync_api import sync_playwright

run = root / '.build/ui-verification' / str(uuid.uuid4())
run.mkdir(parents=True)
ui = root / 'loglens-ui/src/main/resources/ui'
def final_record(sequence):
    return dict(recordSequence=sequence, recordId=f's:r:{sequence}', firstSeen=1000 + sequence, repeatCount=2,
                level='ERROR' if sequence == 1 else 'INFO', logger='orders',
                message='<img src=x onerror=alert(1)>' if sequence == 1 else f'order event {sequence}',
                fields={'orderId': f'O-{sequence}'}, mdc={}, tags=['error'],
                exception={'type': 'Failure'} if sequence == 1 else None, status='FINAL')

final_records = [final_record(sequence) for sequence in range(1, 102)]
captured_records = [dict(captureSequence=sequence, eventId=f's:c:{sequence}', capturedAt=2000 + sequence,
                         logger='orders', level='INFO', message=f'captured event {sequence}', status='CAPTURED_ONLY')
                    for sequence in range(1, 102)]
requests = []
mode = 'history'
def serve(route):
    path = route.request.url.split('127.0.0.1:9876', 1)[1].split('?', 1)[0]
    request = route.request
    query = dict(part.split('=', 1) for part in request.url.split('?', 1)[1].split('&')) if '?' in request.url else {}
    requests.append((path, query))
    if path == '/api/session':
        response = {'application': 'browser-test', 'sessionId': 's', 'readOnly': mode == 'history', 'complete': False}
    elif path == '/api/records':
        after = int(query.get('cursor', '0'))
        through = int(query.get('through', str(2**63 - 1)))
        selected = [record for record in final_records if after < record['recordSequence'] <= through]
        if query.get('level'):
            selected = [record for record in selected if record['level'] == query['level']]
        items = selected[:100]
        response = {'items': items, 'nextCursor': items[-1]['recordSequence'] if items else after,
                    'hasMore': len(selected) > len(items)}
    elif path == '/api/captured':
        after = int(query.get('cursor', '0'))
        selected = [record for record in captured_records if record['captureSequence'] > after]
        items = selected[:100]
        response = {'items': items, 'nextCursor': items[-1]['captureSequence'] if items else after,
                    'hasMore': len(selected) > len(items)}
    elif path == '/api/health':
        response = {'outputHealthy': True, 'dropped': 0}
    elif path.endswith('/sources'):
        response = {'expired': False, 'page': {'items': [], 'nextCursor': 0, 'hasMore': False}}
    else:
        response = None
    if response is not None:
        route.fulfill(content_type='application/json', body=json.dumps(response)); return
    filename = 'index.html' if path == '/' else path.lstrip('/')
    if filename in ['index.html', 'style.css', 'app.js']:
        mime = {'index.html': 'text/html', 'style.css': 'text/css', 'app.js': 'text/javascript'}[filename]
        route.fulfill(content_type=mime, body=(ui / filename).read_text(encoding='utf-8')); return
    route.fulfill(status=404, body='not found')

with sync_playwright() as p:
    context = p.chromium.launch_persistent_context(str(run / 'profile'), executable_path='C:/Program Files/Google/Chrome/Application/chrome.exe', headless=True, viewport={'width': 1440, 'height': 900}, args=['--disable-crash-reporter'])
    context.route('http://127.0.0.1:9876/**', serve)
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.goto('http://127.0.0.1:9876/')
    page.locator('.log-row').first.wait_for()
    assert page.locator('.log-row').count() > 0
    assert page.locator('img').count() == 0
    first_row = page.locator('.log-row').filter(has_text='<img src=x onerror=alert(1)>')
    assert first_row.get_attribute('role') == 'button'
    first_row.focus()
    assert first_row.evaluate("element => getComputedStyle(element).outlineStyle !== 'none'")
    page.keyboard.press('Enter')
    assert page.locator('#focus-order').is_visible()
    assert page.locator('#exception').is_visible()
    page.locator('.log-row').nth(1).focus()
    page.keyboard.press('Space')
    assert 'order event 2' in page.locator('#content').inner_text()
    first_row = page.locator('.log-row').filter(has_text='<img src=x onerror=alert(1)>')
    first_row.focus()
    page.keyboard.press('Enter')
    assert page.locator('#next').is_enabled()
    page.locator('#next').click()
    page.wait_for_function("() => document.querySelector('#next').disabled")
    assert any(path == '/api/records' and query.get('cursor') == '100' for path, query in requests)
    page.locator('select[name="level"]').select_option('ERROR')
    page.get_by_role('button', name='应用筛选').click()
    page.locator('.log-row').filter(has_text='<img src=x onerror=alert(1)>').wait_for()
    assert page.locator('.log-row').count() == 1
    page.locator('#captured-view').click()
    page.locator('.log-row').filter(has_text='captured event 1').first.wait_for()
    page.locator('.log-row').filter(has_text='captured event 1').first.click()
    assert page.locator('#sources').is_hidden()
    assert page.locator('#focus-order').is_hidden()
    assert 'CAPTURED_ONLY' in page.locator('#content').inner_text()
    assert page.locator('select[name="level"]').is_disabled()
    assert page.locator('.log-row').count() > 0
    page.locator('#next').click()
    page.wait_for_function("() => document.querySelector('#next').disabled")
    assert any(path == '/api/captured' and query.get('cursor') == '100' for path, query in requests)
    page.locator('#theme').click()
    assert page.locator('body').evaluate('(element)=>element.classList.contains("light")')
    assert page.locator('#notice').is_visible()
    page.screenshot(path=str(run / 'light.png'), full_page=True)
    assert not errors, errors
    mode = 'live'
    live = context.new_page()
    live.on('pageerror', lambda error: errors.append(str(error)))
    live.add_init_script("""class FixtureEventSource extends EventTarget {
      constructor(url) { super(); this.url=url; this.closed=false; window.testEventSource=this; }
      close() { this.closed=true; }
    }; window.EventSource=FixtureEventSource;""")
    live.goto('http://127.0.0.1:9876/')
    live.locator('#connection').wait_for()
    live.evaluate("window.testEventSource.dispatchEvent(new MessageEvent('ready',{data:'{\\\"watermark\\\":1}'}))")
    live.wait_for_function("() => document.querySelector('#connection').textContent.includes('实时已连接')")
    live.locator('.log-row').filter(has_text='img src=x').wait_for()
    live.locator('#pause').click()
    live.evaluate("record => window.testEventSource.dispatchEvent(new MessageEvent('record',{data:JSON.stringify(record)}))", final_record(102))
    live.locator('.log-row').filter(has_text='order event 102').wait_for()
    assert '1' in live.locator('#pause').inner_text(), 'Paused follow must count incoming records'
    live.locator('#pause').click()
    assert live.locator('#pause').inner_text() == '暂停跟随', 'Resume must clear the new-record count'
    assert not errors, errors
    (run / 'results.json').write_text(json.dumps({'errors': errors, 'textSafety': True, 'theme': True,
        'details': True, 'historyPages': 2, 'capturedOnlyPages': 2, 'capturedFiltersDisabled': True,
        'liveWatermark': True, 'pausedReceiveAndResume': True, 'keyboardRows': True,
        'enterAndSpaceOpenDetails': True, 'keyboardFocusVisible': True}), encoding='utf-8')
    context.close()
print(f'Browser UI smoke verified: {run}')
