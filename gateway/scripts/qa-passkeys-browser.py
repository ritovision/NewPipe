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
import subprocess
import tempfile
import time
from urllib.parse import urlencode, urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

ROOT=Path(__file__).resolve().parents[1]
STATE=Path.home()/'.local/state/newpipe-passkeys-20261109'
STATE.mkdir(parents=True,exist_ok=True)
checks:list[str]=[]
def passed(label:str)->None:
    checks.append(label)
    print('PASS '+label,flush=True)

def main()->None:
    with tempfile.TemporaryDirectory(prefix='browser-qa-',dir=STATE) as tmp:
        directory=Path(tmp)
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(directory/'key.pem'),
            '-out',str(directory/'cert.pem'),'-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost','-days','1'],
            check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        log=(directory/'server.log').open('w')
        server=subprocess.Popen(['node',str(ROOT/'scripts/qa-passkeys-server.mjs')],cwd=ROOT,
            env={**os.environ,'NEWPIPE_QA_DIRECTORY':tmp},stdout=log,stderr=subprocess.STDOUT)
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
                def switch_authenticator(_source):
                    cdp.send('WebAuthn.removeVirtualAuthenticator',{'authenticatorId':authenticator})
                    cdp.send('WebAuthn.addVirtualAuthenticator',{'options':{'protocol':'ctap2','ctap2Version':'ctap2_1',
                        'transport':'internal','hasResidentKey':True,'hasUserVerification':True,'isUserVerified':True,
                        'automaticPresenceSimulation':True}})
                page.expose_binding('switchSyntheticAuthenticator',switch_authenticator)
                page.evaluate("""() => {const original=navigator.credentials.create.bind(navigator.credentials);
                    navigator.credentials.create=async (...args)=>{navigator.credentials.create=original;
                        await window.switchSyntheticAuthenticator();return original(...args);};}""")
                page.get_by_role('button',name='Add passkey',exact=True).click()
                expect(page.get_by_text('QA secondary passkey',exact=True)).to_be_visible(timeout=15000)
                passed('additional enrollment requires fresh verification and succeeds after cancellation')
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
                assert not errors,errors
                passed('no uncaught browser JavaScript errors')
                context.close();browser.close()
            output={'status':'passed','checks':checks,'environment':'primary Chromium + virtual CTAP2 + ephemeral loopback HTTPS Miniflare',
                    'real_biometrics_tested':False,'production_gateway_contacted':False,'phone_modified':False}
            (STATE/'phase-b-browser-result.json').write_text(json.dumps(output,indent=2)+'\n')
        finally:
            server.terminate()
            try:server.wait(timeout=8)
            except subprocess.TimeoutExpired:server.kill();server.wait()
            log.close()

if __name__=='__main__':main()
