#!/usr/bin/env python3
"""Primary-only opt-in isolated live Worker checks using a disposable authenticator.

Never reads a production owner key. Bootstrap registration options are issued by
an already-authenticated QA owner on its own workstation; only public WebAuthn
options/attestation cross machines. The virtual private key remains in a mode-600
local file until QA cleanup. This is not a physical biometric or ChatGPT test.
"""
from __future__ import annotations
import base64,hashlib,json,os,re,secrets,sys,time,uuid
from pathlib import Path
from urllib.parse import urlencode,urlparse,parse_qs
from playwright.sync_api import sync_playwright,expect
STATE=Path.home()/'.local/state/newpipe-passkeys-20261109/staging'
STATE.mkdir(mode=0o700,parents=True,exist_ok=True)
os.umask(0o077)
ORIGIN=os.environ.get('NEWPIPE_QA_ORIGIN','')
if not re.fullmatch(r'https://np-passkeys-qa-20261109-[0-9a-f]{4}\.matthewjpellerito\.workers\.dev',ORIGIN):
    raise SystemExit('Explicit disposable QA origin required; production is forbidden')
ACTION=sys.argv[1]
assert ACTION in ('register','signin','approve','deny','oauth','refresh','call','anonymous')
def save(name,value):
    path=STATE/name;path.write_text(json.dumps(value,indent=2)+'\n');os.chmod(path,0o600)
def load(name):return json.loads((STATE/name).read_text())
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path='/usr/bin/google-chrome')
    context=browser.new_context(viewport={'width':1080,'height':900})
    def route(r):
        if r.request.url.startswith(ORIGIN+'/'):r.continue_()
        elif r.request.url.startswith('https://example.com/callback'):
            r.fulfill(status=200,content_type='text/html',body='<h1>Isolated QA callback</h1>')
        else:r.abort()
    context.route('**/*',route)
    page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    cdp=context.new_cdp_session(page);cdp.send('WebAuthn.enable',{'enableUI':False})
    aid=cdp.send('WebAuthn.addVirtualAuthenticator',{'options':{'protocol':'ctap2','ctap2Version':'ctap2_1','transport':'internal','hasResidentKey':True,'hasUserVerification':True,'isUserVerified':True,'automaticPresenceSimulation':True}})['authenticatorId']
    if ACTION not in ('register','anonymous'):
        for c in load('virtual-credentials.json'):
            cdp.send('WebAuthn.addCredential',{'authenticatorId':aid,'credential':c})
    try:
        if ACTION=='register':
            request=load('public-registration-options.json');options=request['options']
            assert options['rp']['id']==urlparse(ORIGIN).hostname
            assert options['authenticatorSelection']['userVerification']=='required'
            page.goto(ORIGIN+'/setup');assert page.evaluate('isSecureContext')
            response=page.evaluate('''async options => {
                const credential=await navigator.credentials.create({publicKey:PublicKeyCredential.parseCreationOptionsFromJSON(options)});
                return credential.toJSON();
            }''',options)
            save('public-attestation.json',response)
            save('virtual-credentials.json',cdp.send('WebAuthn.getCredentials',{'authenticatorId':aid})['credentials'])
            print('PASS actual Chromium created a resident public attestation for the live QA relying party. Virtual private key stayed local.')
            print(json.dumps(response,separators=(',',':')))
        elif ACTION in ('signin','approve','deny','oauth'):
            page.goto(ORIGIN+'/setup')
            expect(page.get_by_role('button',name='Sign in with passkey',exact=True)).to_be_visible()
            page.get_by_role('button',name='Sign in with passkey',exact=True).click()
            expect(page.get_by_text('Disposable primary browser QA',exact=True)).to_be_visible(timeout=20000)
            print('PASS actual bundled sign-in UI + live WebAuthn assertion; no owner-key entry.')
            if ACTION in ('approve','deny'):
                request_id,code=sys.argv[2:4]
                assert re.fullmatch('[0-9a-f]{64}',request_id) and re.fullmatch('[0-9A-F]{4}-[0-9A-F]{4}',code)
                page.goto(ORIGIN+'/pair/device?request='+request_id)
                expect(page.get_by_role('heading',name='Approve this NewPipe device?',exact=True)).to_be_visible()
                expect(page.get_by_text('Isolated live Pixel QA',exact=True)).to_be_visible()
                expect(page.get_by_text(code,exact=True)).to_be_visible()
                comparison=page.get_by_label('I started setup in NewPipe and its code matches.',exact=True)
                assert not comparison.is_checked()
                if ACTION=='approve':comparison.check()
                page.get_by_role('button',name='Approve this device' if ACTION=='approve' else 'Deny',exact=True).click()
                expect(page.get_by_role('heading',name='Device approved' if ACTION=='approve' else 'Device not approved',exact=True)).to_be_visible()
                print('PASS live browser '+ACTION+' for exact Pixel request/code with explicit consent.')
            elif ACTION=='oauth':
                r=context.request.post(ORIGIN+'/oauth/register',data={'client_name':'Isolated live browser acceptance','redirect_uris':['https://example.com/callback'],'grant_types':['authorization_code','refresh_token'],'response_types':['code'],'token_endpoint_auth_method':'none'})
                assert r.status==201;client=r.json();verifier=secrets.token_urlsafe(32)
                challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip('=')
                q=urlencode({'response_type':'code','client_id':client['client_id'],'redirect_uri':'https://example.com/callback','scope':'mcp.connect library.read playback.control playlist.write','state':'isolated-live-qa','code_challenge':challenge,'code_challenge_method':'S256','resource':ORIGIN+'/mcp'})
                page.goto(ORIGIN+'/authorize?'+q)
                expect(page.get_by_role('button',name='Authorize',exact=True)).to_be_visible()
                expect(page.locator('input[name="owner_key"]')).to_have_count(0)
                page.get_by_role('button',name='Authorize',exact=True).click();page.wait_for_url('https://example.com/callback*',timeout=20000)
                redirect=parse_qs(urlparse(page.url).query);assert redirect['state']==['isolated-live-qa']
                r=context.request.post(ORIGIN+'/oauth/token',form={'grant_type':'authorization_code','client_id':client['client_id'],'redirect_uri':'https://example.com/callback','code_verifier':verifier,'code':redirect['code'][0],'resource':ORIGIN+'/mcp'})
                assert r.status==200;token=r.json();assert token['expires_in']==600 and token.get('refresh_token')
                save('live-oauth.json',{**token,'client_id':client['client_id']})
                print('PASS live browser consent, PKCE exchange and 600-second access; selected OAuth scopes only.')
        elif ACTION=='refresh':
            t=load('live-oauth.json');r=context.request.post(ORIGIN+'/oauth/token',form={'grant_type':'refresh_token','client_id':t['client_id'],'refresh_token':t['refresh_token'],'resource':ORIGIN+'/mcp'})
            assert r.status==200;rj=r.json();assert rj['expires_in']==600 and rj['refresh_token']!=t['refresh_token']
            save('live-oauth.json',{**rj,'client_id':t['client_id']});print('PASS live refresh rotates credentials and retains 600-second access.')
        elif ACTION=='call':
            name=sys.argv[2];arguments=json.loads(sys.argv[3]);assert name in ('list_devices','get_device_status','get_catalog_status','pause','create_playlist')
            if name!='list_devices':assert re.fullmatch('[0-9a-f-]{36}',arguments.get('device_id',''))
            if name in ('pause','create_playlist'):arguments.update(request_id=str(uuid.uuid4()),issued_at_ms=int(time.time()*1000))
            t=load('live-oauth.json');r=context.request.post(ORIGIN+'/mcp',data={'jsonrpc':'2.0','id':1,'method':'tools/call','params':{'name':name,'arguments':arguments}},headers={'Authorization':'Bearer '+t['access_token'],'Accept':'application/json, text/event-stream','MCP-Protocol-Version':'2025-11-25'})
            result={'http_status':r.status,'tool':name,'result':r.json()};save('last-mcp-result.json',result);print(json.dumps(result,indent=2))
        else:
            page.goto(ORIGIN+'/setup');expect(page.get_by_role('button',name='Sign in with passkey')).to_be_visible();assert page.evaluate('isSecureContext')
            r=context.request.post(ORIGIN+'/auth/register/options',data={'csrf':'wrong','label':'Rejected'},headers={'Origin':ORIGIN})
            assert r.status in (400,401,403)
            print('PASS live trusted TLS page loads and anonymous credential enrollment is rejected.')
        assert not errors,errors
    finally:
        # Persist only the synthetic authenticator counter/key locally between QA commands.
        if ACTION not in ('anonymous','register'):
            credentials=cdp.send('WebAuthn.getCredentials',{'authenticatorId':aid})['credentials']
            if credentials:save('virtual-credentials.json',credentials)
        context.close();browser.close()
