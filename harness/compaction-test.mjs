import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {mkdtemp,rm} from 'node:fs/promises';
import {HarnessRuntime} from './runtime.mjs';
let summaryRequests=0, modelRequests=0, calls=0, usedCheckpoint=false;
const facts='Goal: replace Calculator.add(II)I with 42; project=demo, sample=r3, source=base.apk!classes.dex, pluginId=plugin-123, revision=7, status=LOADED; unknown writes must be queried; pending: verify log and unload; outputId=123e4567-e89b-12d3-a456-426614174000.';
function validatePairs(messages) {
  const pending=new Set();
  for(const message of messages) {
    if (message.role==='tool') { assert.ok(pending.delete(message.tool_call_id),'orphan tool result'); continue; }
    assert.equal(pending.size,0,'unpaired tool call');
    for(const call of message.tool_calls??[]) pending.add(call.id);
  }
  assert.equal(pending.size,0,'unfinished tool pair');
}
const server=createServer(async(request,response)=>{
 if(request.method!=='POST'){response.writeHead(405).end();return;}
 let raw=''; for await(const chunk of request) raw+=chunk;
 const body=JSON.parse(raw); response.setHeader('Content-Type','application/json');
 if(request.url==='/mcp') {
  if(body.id==null){response.writeHead(202).end();return;}
  const result=body.method==='initialize'?{protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:'fixture',version:'1'}}:body.method==='tools/list'?{tools:[{name:'get_project_info',description:'Read bound state',inputSchema:{type:'object',properties:{}}}]}:body.method==='tools/call'?{content:[{type:'text',text:JSON.stringify({facts,call:++calls})}]}:undefined;
  response.end(JSON.stringify({jsonrpc:'2.0',id:body.id,...(result?{result}:{error:{code:-32601,message:'Unknown method'}})}));return;
 }
 validatePairs(body.messages);
 const serialized=JSON.stringify(body.messages);
 if(serialized.includes('fork-verify-before')) {assert.ok(!serialized.includes('Turn 1;'));assert.ok(!serialized.includes('Turn 11;'));}
 if(serialized.includes('fork-verify-after')) assert.ok(!serialized.includes('Turn 11;'));
 const last=body.messages.at(-1);
 const summary=typeof last.content==='string'&&last.content.includes('You are now acting as a compaction engine');
 let delta,finish;
 if(summary){summaryRequests++;delta={content:`## Primary Request and Intent\n${facts}\n## Pending Jobs\nVerify actual log; unload plugin-123.\n## Current Work\n${facts}`};finish='stop';}
 else {
  modelRequests++;if(JSON.stringify(body.messages).includes('<compacted-summary>')) {usedCheckpoint=true;assert.ok(JSON.stringify(body.messages).includes('plugin-123'));assert.ok(JSON.stringify(body.messages).includes('revision=7'));}
  if(last.role==='tool'){delta={content:`Confirmed ${facts}\n`+'Analysis detail. '.repeat(400)};finish='stop';}
  else {delta={tool_calls:[{index:0,id:`read-${modelRequests}`,type:'function',function:{name:'mcp__easylsp__get_project_info',arguments:'{}'}}]};finish='tool_calls';}
 }
 response.setHeader('Content-Type','text/event-stream');
 response.end('data: '+JSON.stringify({id:'fixture',choices:[{index:0,delta,finish_reason:finish}]})+'\n\ndata: [DONE]\n\n');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const directory=await mkdtemp('/tmp/easylsp-compaction-');const runtime=new HarnessRuntime();const events=[];
runtime.on('notification',frame=>{if(frame.method==='session.event')events.push(frame.params.event)});
const base=`http://127.0.0.1:${server.address().port}`;
try {
 await runtime.start({api:'openai-completions',baseURL:base+'/v1',model:'fixture',secret:'fixture',bridgeURL:base+'/mcp',bridgeToken:'fixture',contextWindow:1000000,contextBudget:16384,maxTokens:1024,directory,prompt:'Preserve exact user goals, constraints, signatures, sample, plugin revision/runtime state, failures, pending work and result references in checkpoints.'});
 for(let i=0;i<12;i++) {
  await runtime.rpc('session/prompt',{sessionId:'compaction-test',contentBlocks:[{type:'text',text:`Turn ${i}; ${facts}\n`+'Additional task context. '.repeat(180)}]});
  await runtime.rpc('session/wait',{sessionId:'compaction-test'});
 }
 assert.ok(summaryRequests>0,'must actually compact');assert.ok(usedCheckpoint,'next request must use checkpoint');
 assert.ok(calls>=12);assert.equal(calls,events.filter(e=>e.type==='tool/call').length);assert.equal(calls,events.filter(e=>e.type==='tool/result').length);
 assert.equal(events.filter(e=>e.type==='turn/end'&&e.data.reason.kind==='completed').length,12);
 const users=events.filter(e=>e.type==='user/message' && e.data.source.kind==='user');
 const parentCalls=calls;
 for (const [label,user] of [['before',users[1]],['after',users.at(-1)]]) {
  const seed=await runtime.control('session/fork-seed',{sessionId:'compaction-test',messageId:user.data.id});
  const fork=new HarnessRuntime();const forkId=`fork-${label}`;
  try {
   await fork.start({api:'openai-completions',baseURL:base+'/v1',model:'fixture',secret:'fixture',bridgeURL:base+'/mcp',bridgeToken:'fixture',contextWindow:1000000,contextBudget:16384,maxTokens:1024,directory:`${directory}/${forkId}`});
   await fork.control('session/seed',{sessionId:forkId,...seed,cwd:`${directory}/${forkId}`});
   await fork.rpc('session/resume',{sessionId:forkId});
   const priorCalls=calls;
   await fork.rpc('session/prompt',{sessionId:forkId,contentBlocks:[{type:'text',text:`fork-verify-${label}`}]});
   await fork.rpc('session/wait',{sessionId:forkId});
   assert.equal(calls,priorCalls+1,'only the new requested tool executes after a fork');
  } finally {await fork.close();}
 }
 assert.equal(calls,parentCalls+2);
 console.log(JSON.stringify({capacity:1000000,testBudget:16384,summaryRequests,modelRequests,calls,usedCheckpoint,toolPairsValid:true,preservedFacts:true,forkBeforeAndAfterCompaction:true,oldToolsReplayed:0,durableEvents:events.length,eventTypes:[...new Set(events.map(e=>e.type))]}));
}finally{await runtime.close();server.closeAllConnections();server.close();await rm(directory,{recursive:true});}
