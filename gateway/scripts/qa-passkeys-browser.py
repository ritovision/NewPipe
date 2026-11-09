#!/usr/bin/env python3
"""Run ONLY on the primary workstation. No live gateway/phone/user passkeys.

Requires the existing Playwright Python environment and Chromium. Creates a
loopback HTTPS Miniflare test gateway, disposable browser profile and virtual
CTAP2 authenticator. All synthetic credentials/certificates are removed afterward.
"""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import tempfile
import time
import uuid
from urllib.parse import urlencode, urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
STATE=Path.home()/'.local/state/newpipe-passkeys-20261109'
STATE.mkdir(parents=True,exist_ok=True)
checks:list[str]=[]
def passed(label:str)->None:
    checks.append(label)
    print('PASS '+label,flush=True)

class SyntheticPairing:
    """Disposable P-256 device, exercising real browser approval with signed HTTP calls."""
    def __init__(self, context, origin: str, directory: Path):
        self.context=context;self.origin=origin
        device=str(uuid.uuid4());self.key=directory/(device+'.pem')
        subprocess.run(['openssl','genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:P-256','-out',str(self.key)],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        public=subprocess.check_output(['openssl','pkey','-in',str(self.key),'-pubout','-outform','DER'],stderr=subprocess.DEVNULL)
        nonce=context.request.get(origin+'/device/pairing/nonce');assert nonce.status==200,nonce.text()
        self.details={'pairing_version':1,'device_id':device,'public_key':self.b64(public),'client_nonce':nonce.json()['client_nonce'],
            'issued_at_ms':int(time.time()*1000),'name':'Browser QA phone','permissions':['library.read','playback.control']}
        self.id=hashlib.sha256(self.message('begin').encode()).hexdigest()
        self.url=origin+'/pair/device?request='+self.id
        self.code=self.id[:4].upper()+'-'+self.id[4:8].upper()
        result=self.call('begin');assert result.status==200,result.text()
    @staticmethod
    def b64(value: bytes)->str:return base64.urlsafe_b64encode(value).decode().rstrip('=')
    def message(self, operation: str)->str:
        d=self.details
        return '\n'.join(['newpipe-pairing-v1',operation,self.origin,d['device_id'],d['public_key'],d['client_nonce'],
            str(d['issued_at_ms']),d['name'],','.join(d['permissions']),'' if operation=='begin' else self.id])
    def call(self, operation: str):
        signature=subprocess.check_output(['openssl','dgst','-sha256','-sign',str(self.key)],input=self.message(operation).encode(),stderr=subprocess.DEVNULL)
        payload={**self.details,'signature':self.b64(signature)}
        if operation!='begin':payload['request_id']=self.id
        return self.context.request.post(self.origin+'/device/pairing/'+operation,data=payload)

def main()->None:
    with tempfile.TemporaryDirectory(prefix='browser-qa-',dir=STATE) as tmp:
        directory=Path(tmp)
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(directory/'key.pem'),
            '-out',str(directory/'cert.pem'),'-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-days','1'],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        log=(directory/'server.log').open('w')
        server=subprocess.Popen(['node',str(ROOT/'scripts/qa-passkeys-server.mjs')],cwd=ROOT,
            env={**os.environ,'NEWPIPE_QA_DIRECTORY':tmp},stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            for _ in range(200):
                if (directory/'ready.json').exists():break
                if server.poll() is not None:raise RuntimeError('Synthetic server exited; see its temporary log')
                time.sleep(.1)
            config=json.loads((directory/'ready.json').read_text())
            origin=config['origin'];owner_key=config['ownerKey']
            with sync_playwright() as p:
                browser=p.chromium.launch(headless=True,executable_path='/usr/bin/google-chrome',args=['--ignore-certificate-errors'])
                context=browser.new_context(ignore_https_errors=True,viewport={'width':1080,'height':900})
                # Never navigate to an Internet callback or contact a live gateway.
                def route(request):
                    if request.request.url.startswith(origin+'/'):request.continue_()
                    elif request.request.url.startswith('https://example.com/callback'):
                        request.fulfill(status=200,content_type='text/html',body='<h1>Synthetic client callback</h1>')
                    else:request.abort()
                context.route('**/*',route)
                page=context.new_page();errors=[]
                page.on('pageerror',lambda error:errors.append(str(error)))
                cdp=context.new_cdp_session(page)
                cdp.send('WebAuthn.enable',{'enableUI':False})
                authenticator=cdp.send('WebAuthn.addVirtualAuthenticator',{'options':{'protocol':'ctap2','ctap2Version':'ctap2_1',
                    'transport':'internal','hasResidentKey':True,'hasUserVerification':True,'isUserVerified':True,
                    'automaticPresenceSimulation':True}})['authenticatorId']
                page.goto(origin+'/setup')
                expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible()
                assert page.evaluate('window.isSecureContext')
                passed('passkey-first page loads securely with its actual local browser bundle')
                page.get_by_text('First setup or emergency recovery',exact=True).click()
                page.get_by_label('Owner setup key',exact=True).fill(owner_key)
                page.get_by_role('button',name='Recover access / first setup',exact=True).click()
                expect(page.get_by_role('button',name='Add passkey',exact=True)).to_be_visible()
                page.get_by_label('New passkey label',exact=True).fill('QA primary passkey')
                page.get_by_role('button',name='Add passkey',exact=True).click()
                expect(page.get_by_text('QA primary passkey',exact=True)).to_be_visible(timeout=15000)
                credentials=cdp.send('WebAuthn.getCredentials',{'authenticatorId':authenticator})['credentials']
                assert len(credentials)==1 and credentials[0]['isResidentCredential']
                passed('one-time recovery bootstraps a resident passkey through navigator.credentials')
                page.get_by_role('button',name='Sign out',exact=True).click()
                expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible()
                page.get_by_role('button',name='Sign in with passkey').click()
                expect(page.get_by_text('QA primary passkey',exact=True)).to_be_visible(timeout=15000)
                passed('returning owner signs in with the virtual authenticator and no owner-key entry')
                # Real browser cancellation path: one controlled NotAllowedError in
                # navigator.credentials.create, then restore the native implementation.
                page.get_by_label('New passkey label',exact=True).fill('QA secondary passkey')
                page.evaluate("""() => {const original=navigator.credentials.create.bind(navigator.credentials);
                    navigator.credentials.create=(...args)=>{navigator.credentials.create=original;
                        return Promise.reject(new DOMException('Synthetic cancellation','NotAllowedError'));};} """)
                page.get_by_role('button',name='Add passkey',exact=True).click()
                expect(page.locator('#auth-status')).to_contain_text('cancelled',timeout=15000)
                passed('cancelled enrollment returns a usable page without issuing a credential')
                # Switch virtual devices between browser operations. Sending CDP
                # commands inside a synchronous page binding deadlocks this runner.
                # Fresh recovery proof authorizes this second-device registration;
                # existing-passkey step-up was exercised by the cancelled attempt.
                cdp.send('WebAuthn.removeVirtualAuthenticator',{'authenticatorId':authenticator})
                cdp.send('WebAuthn.addVirtualAuthenticator',{'options':{'protocol':'ctap2','ctap2Version':'ctap2_1',
                    'transport':'internal','hasResidentKey':True,'hasUserVerification':True,'isUserVerified':True,
                    'automaticPresenceSimulation':True}})
                page.get_by_text('Recovery and owner-session reset',exact=True).click()
                page.get_by_label('Recovery key for the next sensitive action',exact=True).fill(owner_key)
                page.get_by_role('button',name='Add passkey',exact=True).click()
                expect(page.get_by_text('QA secondary passkey',exact=True)).to_be_visible(timeout=15000)
                passed('additional device enrollment succeeds with fresh recovery verification after cancellation')
                # Start an OAuth connection while signed out, preserving original
                # transaction cookies through passkey login and explicit approval.
                page.get_by_role('button',name='Sign out',exact=True).click()
                registered=context.request.post(origin+'/oauth/register',data={'client_name':'Synthetic browser client',
                    'redirect_uris':['https://example.com/callback'],'grant_types':['authorization_code','refresh_token'],
                    'response_types':['code'],'token_endpoint_auth_method':'none'})
                assert registered.status==201
                client=registered.json();verifier=secrets.token_urlsafe(32)
                challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
                query=urlencode({'response_type':'code','client_id':client['client_id'],'redirect_uri':'https://example.com/callback',
                    'scope':'mcp.connect library.read library.delete','state':'browser-qa-state','code_challenge':challenge,
                    'code_challenge_method':'S256','resource':origin+'/mcp'})
                page.goto(origin+'/authorize?'+query)
                expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible()
                expect(page.get_by_role('button',name='Authorize',exact=True)).to_have_count(0)
                page.get_by_role('button',name='Sign in with passkey').click()
                expect(page.get_by_role('button',name='Authorize',exact=True)).to_be_visible(timeout=15000)
                expect(page.locator('input[name="owner_key"]')).to_have_count(0)
                assert not page.locator('input[value="library.delete"]').is_checked()
                assert page.locator('input[value="library.read"]').is_checked()
                passed('OAuth login returns to explicit consent; destructive scope remains unchecked')
                page.get_by_role('button',name='Authorize',exact=True).click()
                page.wait_for_url('https://example.com/callback*',timeout=15000)
                redirect=parse_qs(urlparse(page.url).query)
                assert redirect['state']==['browser-qa-state']
                exchange=context.request.post(origin+'/oauth/token',form={'grant_type':'authorization_code','client_id':client['client_id'],
                    'redirect_uri':'https://example.com/callback','code_verifier':verifier,'code':redirect['code'][0],'resource':origin+'/mcp'})
                assert exchange.status==200
                token=exchange.json();assert token['scope']=='mcp.connect library.read' and token['expires_in']==600
                passed('browser-approved OAuth code exchanges with PKCE and only the approved scopes')
                page.goto(origin+'/setup')
                expect(page.get_by_text('QA primary passkey',exact=True)).to_be_visible()
                passed('cross-site return reuses the owner browser session')
                # Both authenticated and anonymous denial must reach the callback
                # under the same CSP rules; neither should issue an OAuth code.
                page.goto(origin+'/authorize?'+query)
                expect(page.get_by_role('button',name='Deny',exact=True)).to_be_visible()
                page.get_by_role('button',name='Deny',exact=True).click()
                page.wait_for_url('https://example.com/callback*',timeout=15000)
                denied=parse_qs(urlparse(page.url).query)
                assert denied.get('error')==['access_denied'] and 'code' not in denied
                passed('authenticated denial reaches the external callback under CSP')
                page.goto(origin+'/setup');page.get_by_role('button',name='Sign out',exact=True).click()
                page.goto(origin+'/authorize?'+query)
                page.get_by_role('button',name='Deny connection',exact=True).click()
                page.wait_for_url('https://example.com/callback*',timeout=15000)
                denied=parse_qs(urlparse(page.url).query)
                assert denied.get('error')==['access_denied'] and 'code' not in denied
                passed('anonymous denial reaches the external callback without owner authentication')
                # Browser-mediated enrollment: possession of a URL never authorizes a phone.
                phone=SyntheticPairing(context,origin,directory)
                page.goto(phone.url)
                expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible()
                page.get_by_role('button',name='Sign in with passkey').click()
                expect(page.get_by_role('heading',name='Approve this NewPipe device?',exact=True)).to_be_visible(timeout=15000)
                expect(page.get_by_text(phone.code,exact=True)).to_be_visible()
                expect(page.locator('input[name="owner_key"]')).to_have_count(0)
                passed('device enrollment returns from passkey sign-in to the exact signed request and comparison code')
                comparison=page.get_by_label('I started setup in NewPipe and its code matches.',exact=True)
                assert not comparison.is_checked()
                page.get_by_role('button',name='Approve this device',exact=True).click()
                assert phone.call('status').json()['status']=='pending'
                passed('browser requires explicit code comparison; sign-in and unchecked approval do not pair')
                comparison.check();page.get_by_role('button',name='Approve this device',exact=True).click()
                expect(page.get_by_role('heading',name='Device approved',exact=True)).to_be_visible()
                assert phone.call('status').json()['status']=='approved'
                finished=phone.call('finish');assert finished.status==200,finished.text()
                assert finished.json()['paired'] and finished.json()['device_id']==phone.details['device_id']
                assert phone.call('finish').json()['status']=='complete'
                passed('explicit browser approval completes only through the same device key and retries are idempotent')
                denied_phone=SyntheticPairing(context,origin,directory)
                page.goto(denied_phone.url)
                expect(page.get_by_role('button',name='Approve this device',exact=True)).to_be_visible()
                page.get_by_role('button',name='Deny',exact=True).click()
                expect(page.get_by_role('heading',name='Device not approved',exact=True)).to_be_visible()
                assert denied_phone.call('status').json()['status']=='denied'
                assert denied_phone.call('finish').status==401
                passed('returning owner can deny pairing without checking the comparison box')
                revoked_phone=SyntheticPairing(context,origin,directory)
                page.goto(revoked_phone.url)
                page.get_by_label('I started setup in NewPipe and its code matches.',exact=True).check()
                page.get_by_role('button',name='Approve this device',exact=True).click()
                expect(page.get_by_role('heading',name='Device approved',exact=True)).to_be_visible()
                page.goto(origin+'/setup');page.get_by_role('button',name='Sign out',exact=True).click()
                expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible()
                assert revoked_phone.call('finish').status==401
                passed('browser logout after approval prevents a not-yet-committed device enrollment')
                assert not errors,errors
                passed('no uncaught browser JavaScript errors')
                context.close();browser.close()
            output={'status':'passed','checks':checks,'environment':'primary Chromium + virtual CTAP2 + ephemeral loopback HTTPS Miniflare',
                    'real_biometrics_tested':False,'production_gateway_contacted':False,'phone_modified':False}
            (STATE/'phase-c-browser-result.json').write_text(json.dumps(output,indent=2)+'\n')
        finally:
            if server.poll() is None:os.killpg(server.pid,signal.SIGTERM)
            try:server.wait(timeout=8)
            except subprocess.TimeoutExpired:os.killpg(server.pid,signal.SIGKILL);server.wait()
            log.close()

if __name__=='__main__':main()
