/** Observe real island targets against a Vite dev server, without replacing WebGL. */
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const browser = await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM ? {executablePath:process.env.PLAYWRIGHT_CHROMIUM} : {})});
const failures=[];
try {
 for(const scenario of ['ordinary','ordinary-again','hidden-window','hidden-blocked-then-ready','hidden-updated-recovered','layout-change','blocked-then-ready','error-then-ready','skip-with-pointer','reduced-motion','reduced-updated']) {
  if (process.env.INTRO_SCENARIO && process.env.INTRO_SCENARIO !== scenario) continue;
  const page=await browser.newPage({viewport:{width:900,height:552},reducedMotion:scenario.startsWith('reduced')?'reduce':'no-preference'});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  // Log the real component's target API; preserve the renderer and all behavior.
  await page.route('**/src/ui/island3d.js',async route=>{
   const response=await route.fetch(),source=await response.text(),seam='set(next = {}) {';
   assert.ok(source.includes(seam),'requires the unminified Vite dev server');
   await route.fulfill({response,body:source.replace('pace(x) {', 'pace(x) { window.__introActive=x===0.7?true:x===1?false:window.__introActive;').replace(seam,`${seam}\n window.__islandTargets.push({built:next.built,hidden:document.hidden,intro:window.__introActive===true,modal:!!document.querySelector('.modal')});`)});
  });
  await page.addInitScript(scenario=>{
   let reads=0;
   window.__islandTargets=[];window.__hiddenFixture=scenario.startsWith('hidden');
   if(window.__hiddenFixture)Object.defineProperty(document,'hidden',{configurable:true,get:()=>window.__hiddenFixture});
   const ready={state:'ready',mod_version:'0.18.1',targets:['lunar','forge'].map(kind=>({kind,state:'ready',message:kind,action:'none',candidates:[]}))};
   const prefs={autoUpdateEnabled:false,autoUpdatePrompted:true,updateChannel:'dev',health:'valid'},current={state:'current',currentVersion:'0.18.1',critical:false};
   window.__TAURI__={core:{invoke:async command=>{
    if(command==='status'||command==='refresh_setup'){if(scenario==='error-then-ready' && reads++===0)throw new Error('fixture detection failure');await new Promise(r=>setTimeout(r,50));return ready;}
    if(command==='open_info'){if(scenario==='blocked-then-ready')await new Promise(r=>setTimeout(r,150));return {version:'0.18.1',updated:scenario.includes('updated'),fresh:false};}
    if(command==='game_theme')return {font:'minecraft',accent:'deep-teal'};
    if(command==='launch_preferences')return {useExternalOverlay:true,health:'valid'};
    if(command==='update_preferences')return prefs;
    if(command==='update_status')return scenario==='blocked-then-ready'?{...current,critical:true,state:'checking'}:current;
    if(command==='check_for_update'){if(scenario==='blocked-then-ready')await new Promise(r=>setTimeout(r,700));return current;}
    if(command==='window_top_inset')return 32;
    if(command==='lobby_state')return {kind:'unavailable'};
    return null;
   }},event:{listen:async(name,handler)=>{if(name==='updater://status'&&(scenario==='hidden-blocked-then-ready'||scenario==='hidden-updated-recovered')){setTimeout(()=>handler({payload:{state:'checking',critical:true}}),300);setTimeout(()=>handler({payload:current}),2200);}return ()=>{};}},window:{getCurrentWindow:()=>({onResized:async()=>()=>{},show:async()=>{
    if(window.__hiddenFixture){window.__hiddenFixture=false;document.dispatchEvent(new Event('visibilitychange'));}
   }})}};
  },scenario);
  await page.goto(process.argv[2]||'http://127.0.0.1:1440/');if(scenario==='error-then-ready')await page.locator('[data-act=refresh]').click();
  await page.locator('.isl3d canvas').waitFor();
  if(scenario==='layout-change'){
   await page.waitForFunction(()=>window.__islandTargets.some(t=>t.built===0));
   await page.evaluate(()=>document.dispatchEvent(new CustomEvent('cobblify:units')));
  }
  if(scenario==='skip-with-pointer'){await page.waitForFunction(()=>window.__islandTargets.some(t=>t.intro&&t.built===0));await page.locator('.gear').click();}
  await page.waitForTimeout(3600);
  if(scenario.includes('updated'))await page.locator('.modal').waitFor({timeout:5000});
  const targets=await page.evaluate(()=>window.__islandTargets.filter(t=>t.intro)),start=targets.findIndex(t=>t.built===0),progression=targets.slice(start).filter(t=>!t.hidden).map(t=>t.built);
  try{
   assert.deepEqual(errors,[]);
   if(scenario.startsWith('reduced'))assert.equal(start,-1,'Reduce Motion shows the complete island immediately');
   else if(scenario==='skip-with-pointer'){assert.ok(start>=0);assert.ok(progression.includes(1),'interaction completes the island');assert.ok(!progression.includes(5/6),'interaction skips the replay');}
   else{assert.ok(start>=0,'every eligible launcher opening starts a build');for(let step=0;step<=6;step++)assert.ok(progression.includes(step/6),`visible build includes step ${step}/6`);}
   if(scenario.includes('updated')){assert.equal(await page.locator('.modal').count(),1,'updated open shows What’s New after replay');assert.ok(!targets.some(t=>t.modal),'modal does not precede replay completion');}
   console.log(`PASS ${scenario}`);
  }catch(error){failures.push(`${scenario}: ${error.message}`);console.log(`FAIL ${scenario}: ${error.message}; visible targets=${JSON.stringify([...new Set(progression)])}`);}
  await page.close();
 }
}finally{await browser.close();}
assert.deepEqual(failures,[],failures.join('\n'));
