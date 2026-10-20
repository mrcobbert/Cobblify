/** Trusted pointer events plus a controlled non-focusing-button browser behavior. */
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const browser = await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM?{executablePath:process.env.PLAYWRIGHT_CHROMIUM}:{})});
try {
 for(const [state,nonFocusingButton,touch] of [['bothReady',false,false],['bothReady',true,false],['lunarAbsent',false,false],['lunarAbsent',true,false],['bothReady',false,true],['lunarAbsent',false,true]]) {
  const page=await browser.newPage({viewport:{width:900,height:552},reducedMotion:'reduce',hasTouch:touch});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  if(nonFocusingButton)await page.addInitScript(()=>{
   // Model a browser clearing menu focus on a mouse press without focusing
   // the clicked button. Use compatibility mousedown so canceling pointerdown
   // affects browser event dispatch, rather than consulting application state.
   document.addEventListener('mousedown',e=>{
    if(e.target.closest?.('.caret,.hero.setup')?.getAttribute('aria-expanded')==='true'){document.activeElement.blur();e.preventDefault();}
   },true);
  });
  await page.goto((process.argv[2]||'http://127.0.0.1:1440/').replace(/\/$/,'')+`/?state=${state}&update=currentDev`);
  const caret=page.locator('.caret,.hero.setup');await caret.waitFor();
  if(touch)await caret.tap();else await caret.click();await page.locator('#client-menu').waitFor();
  const box=await caret.boundingBox();
  await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
  if(!touch)await page.mouse.down();await page.waitForTimeout(80);
  const whilePressed=await page.locator('#client-menu').count();
  if(touch)await caret.tap();else await page.mouse.up();await page.waitForTimeout(100);
  const afterClick=await page.locator('#client-menu').count();
  console.log(JSON.stringify({state,nonFocusingButton,touch,whilePressed,afterClick}));
  assert.equal(whilePressed,1,'second press must not close the menu before the click toggles it');
  assert.equal(afterClick,0,'second click must leave the menu closed');
  assert.equal(await caret.getAttribute('aria-expanded'),'false');
  await caret.focus();await page.keyboard.press('ArrowDown');assert.equal(await page.locator('#client-menu').count(),1,'keyboard still opens');
  await page.keyboard.press('Escape');assert.equal(await page.locator('#client-menu').count(),0,'Escape still closes');
  await caret.click();await page.mouse.click(850,500);assert.equal(await page.locator('#client-menu').count(),0,'outside click still dismisses');
  assert.deepEqual(errors,[]);
  await page.close();
 }
 console.log('PASS: second pointer click closes once, including non-focusing-button behavior');
}finally{await browser.close();}
