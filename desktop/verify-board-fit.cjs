const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

async function checkLayout(page, evidence, name) {
    await page.locator('.board-control[data-fit=true]').waitFor();
    await page.waitForTimeout(450);
    const result=await page.evaluate(()=>{
      const root=document.querySelector('.board-control');
      const rect=element=>{const r=element.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom,right:r.right};};
      const selectors=['.board-header','.board-core','.board-attention','.board-work','.board-panels','.board-panels>.board-card'];
      const panels=selectors.flatMap(selector=>[...root.querySelectorAll(selector)].map(element=>({selector,rect:rect(element)})));
      const lists=[...root.querySelectorAll('.board-paged')].map(element=>{
        const body=element.querySelector('.board-paged-body');
        const rows=[...body.querySelectorAll('a')];
        return {total:Number(element.dataset.total),capacity:Number(element.dataset.pageSize),body:rect(body),rows:rows.map(rect),pager:rect(element.querySelector('.board-pager'))};
      });
      const controls=[...root.querySelectorAll('.board-pager button,.board-support-summary>a,.board-support-summary>button,.board-core-indicator,.board-risk-row')].map(element=>({rect:rect(element),card:rect(element.closest('.board-card,.board-core,.board-attention'))}));
      window.scrollTo(0,100000);const scrollY=window.scrollY;window.scrollTo(0,0);
      return {width:innerWidth,height:innerHeight,scrollHeight:document.documentElement.scrollHeight,clientHeight:document.documentElement.clientHeight,
        scrollWidth:document.documentElement.scrollWidth,clientWidth:document.documentElement.clientWidth,scrollY,root:rect(root),panels,lists,controls,
        zoom:getComputedStyle(document.documentElement).getPropertyValue('--app-zoom').trim(),bodyOverflow:getComputedStyle(document.body).overflow};
    });
    fs.writeFileSync(path.join(evidence,'fit-latest.json'),JSON.stringify({name,...result},null,2));
    assert.equal(result.scrollY,0,name+': the document must not scroll');
    assert.ok(result.scrollHeight<=result.clientHeight+1,name+': no document vertical overflow');
    assert.ok(result.scrollWidth<=result.clientWidth+1,name+': no document horizontal overflow');
    assert.notEqual(result.bodyOverflow,'hidden',name+': content must not be hidden to fake a fit');
    for(const panel of result.panels) assert.ok(panel.rect.y>=-1&&panel.rect.bottom<=result.height+1&&panel.rect.right<=result.width+1,name+': visible '+panel.selector);
    for(const list of result.lists) {
      if(list.total) {assert.ok(list.capacity>=1&&list.rows.length>=1,name+': nonempty lists show >= 1 row');}
      for(const row of list.rows) assert.ok(row.bottom<=list.body.bottom+1,name+': row not clipped by list body');
      assert.ok(list.pager.bottom<=result.height+1,name+': pager in viewport');
    }
    for(const {rect,card} of result.controls) assert.ok(rect.height>0&&rect.bottom<=result.height+1&&rect.bottom<=card.bottom+1&&rect.right<=card.right+1,name+': action remains visible within its panel');

    await page.screenshot({path:path.join(evidence,'fit-'+name+'.png'),animations:'disabled'});
    return {name,...result};
  }

module.exports = async function verifyBoardFit(page, evidence) {
  const cases=[];
  const check=async name=>cases.push(await checkLayout(page,evidence,name));
  const toggleSidebar=async()=>{await page.locator('header button').first().click();await page.waitForTimeout(350);};
  for(const [width,height] of [[1920,1080],[1600,900],[1366,768],[1280,720]]) {
    await page.setViewportSize({width,height});
    await check(`${width}x${height}-normal-expanded`);
    await toggleSidebar();await check(`${width}x${height}-normal-collapsed`);await toggleSidebar();
  }
  await page.setViewportSize({width:1366,height:768});
  for(const large of [false,true]) {
    await page.evaluate(large=>{if(large)document.documentElement.dataset.fontSize='large';else delete document.documentElement.dataset.fontSize;},large);
    await page.waitForTimeout(200);
    await check(`1366x768-${large?'large':'normal'}-expanded`);
    await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor();
    await check(`1366x768-${large?'large':'normal'}-assist-expanded`);
    if(large) {
      const opener=page.getByRole('button',{name:'주의 전체 5건 보기',exact:true});
      await opener.click();const dialog=page.getByRole('dialog');await dialog.waitFor();
      const bounds=await dialog.boundingBox();assert.ok(bounds&&bounds.x>=-1&&bounds.y>=-1&&bounds.x+bounds.width<=1367&&bounds.y+bounds.height<=769,'Large-font detail dialog fits the viewport');
      await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'});assert.equal(await opener.evaluate(element=>element===document.activeElement),true);
    }
    await toggleSidebar();await check(`1366x768-${large?'large':'normal'}-assist-collapsed`);await toggleSidebar();
    await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor({state:'hidden'});
  }
  await page.evaluate(()=>delete document.documentElement.dataset.fontSize);
  for(const height of [700,660]) {
    await page.setViewportSize({width:1366,height});await page.evaluate(()=>document.documentElement.dataset.fontSize='large');
    await check(`1366x${height}-large-native-content`);
    await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor();
    await check(`1366x${height}-large-native-content-assist`);
    await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();await page.locator('#assist-panel').waitFor({state:'hidden'});
  }
  await page.evaluate(()=>delete document.documentElement.dataset.fontSize);
  await page.setViewportSize({width:390,height:844});
  await page.locator('.board-control[data-fit=false]').waitFor();
  assert.ok(await page.locator('.board-panels>.board-card').count()===4,'Mobile flow retains all sections');
  await page.screenshot({path:path.join(evidence,'fit-mobile-fallback.png'),fullPage:true,animations:'disabled'});
  await page.setViewportSize({width:1440,height:950});await page.locator('.board-control[data-fit=true]').waitFor();
  // List paging is functional, and a long full list remains reachable by its original route.
  const first=page.locator('.board-paged').first();
  if(await first.getByRole('button',{name:'다음 쪽',exact:true}).isEnabled()) {
    const before=await first.locator('.board-paged-body').innerText();
    await first.getByRole('button',{name:'다음 쪽',exact:true}).click();
    assert.notEqual(await first.locator('.board-paged-body').innerText(),before);
    await first.getByRole('button',{name:'이전 쪽',exact:true}).click();
  }
  fs.writeFileSync(path.join(evidence,'board-fit-result.json'),JSON.stringify({success:true,cases,mobileUsesFlow:true},null,2));
  console.log('PASS one-screen geometry across '+cases.length+' desktop/font/sidebar/assist layouts, visible rows/actions and mobile fallback');
  return cases.map(({name,width,height,zoom,scrollY})=>({name,width,height,zoom,scrollY}));
};

module.exports.checkLayout=checkLayout;
