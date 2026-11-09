// Ephemeral loopback-only HTTPS adapter for the primary workstation's browser QA.
// Uses fresh synthetic owner credentials and in-memory Miniflare storage; never
// reads Wrangler configuration credentials or connects to a deployed gateway.
import {createServer} from 'node:https';
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {harness,ownerKey} from '../test/helpers.mjs';
const directory=process.env.NEWPIPE_QA_DIRECTORY;
if(!directory)throw new Error('NEWPIPE_QA_DIRECTORY is required');
let h;
const server=createServer({key:await readFile(join(directory,'key.pem')),cert:await readFile(join(directory,'cert.pem'))},async(req,res)=>{
 try {
  if(!h){res.writeHead(503);res.end();return;}
  const chunks=[];let length=0;
  for await(const chunk of req){length+=chunk.length;if(length>100000){res.writeHead(413);res.end();return;}chunks.push(chunk);}
  const headers=new Headers();for(const [name,value]of Object.entries(req.headers))if(value!==undefined)headers.set(name,Array.isArray(value)?value.join(', '):value);
  const reply=await h.get(req.url,{method:req.method,headers,...(chunks.length?{body:Buffer.concat(chunks)}:{})});
  for(const [name,value]of reply.headers)if(name!=='set-cookie')res.setHeader(name,value);
  const cookies=reply.headers.getSetCookie();if(cookies.length)res.setHeader('set-cookie',cookies);
  res.writeHead(reply.status);res.end(Buffer.from(await reply.arrayBuffer()));
 }catch{res.writeHead(500);res.end('Synthetic QA adapter failed');}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin='https://localhost:'+server.address().port;
h=await harness({}, {origin});
await writeFile(join(directory,'ready.json'),JSON.stringify({origin,ownerKey}),{mode:0o600});
console.log('Synthetic browser QA gateway ready on loopback HTTPS.');
let closing=false;
async function close(){if(closing)return;closing=true;server.closeAllConnections();await new Promise(resolve=>server.close(resolve));await h?.mf.dispose();process.exit(0);}
process.once('SIGTERM',close);process.once('SIGINT',close);
