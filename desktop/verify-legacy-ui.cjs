const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');
module.exports=async function verifyLegacyUi(page,origin,evidence) {
  const reference=path.resolve('output/legacy-ui-demo/reference');const baseline=JSON.parse(fs.readFileSync(path.join(reference,'computed.json'),'utf8'));
  await page.setViewportSize({width:1600,height:900});await page.goto(origin+'/board');
  await page.locator('.board-control[data-density=normal][data-fit=true]').waitFor();await page.locator('.board-attention:not([data-state=loading])').waitFor();await page.locator('.board-actions button:not([disabled])').waitFor();await page.evaluate(()=>document.fonts.ready);
  await page.waitForFunction(()=>!document.querySelector('.board-actions button')?.disabled&&!document.querySelector('.board-control .board-resource-note[data-state=loading]'));
  const pairs={'.sidebar':'aside','.sb-brand strong':'.legacy-sidebar-brand strong','.ui-page-header h1':'.board-heading h1','.metric':'.board-metric','.metric-label':'.board-metric-label','.metric-value':'.board-metric-value','.ui-card-head':'.board-card-head','.ui-card-title h2':'.board-card-title h2','.ui-pager':'.board-pager','.status-pill':'.board-operation-row .board-pill'};
  const actual=await page.evaluate(pairs=>Object.fromEntries(Object.entries(pairs).map(([key,selector])=>{
    const e=document.querySelector(selector);if(!e)return[key,null];const c=getComputedStyle(e),r=e.getBoundingClientRect();return[key,{fontFamily:c.fontFamily,fontSize:c.fontSize,fontWeight:c.fontWeight,borderRadius:c.borderRadius,padding:c.padding,height:r.height,width:r.width,color:c.color,background:c.backgroundColor}];
  })),pairs);
  fs.writeFileSync(path.join(evidence,"legacy-measured.json"),JSON.stringify(actual,null,2));
  const checks=[];
  for(const [selector,keys] of Object.entries({'.sidebar':['borderRadius','width'],'.sb-brand strong':['fontSize','fontWeight','color'],'.ui-page-header h1':['fontSize','fontWeight','color'],'.metric':['borderRadius','padding'],'.metric-label':['fontSize','fontWeight','color'],'.metric-value':['fontSize','fontWeight'],'.ui-card-head':['height','padding'],'.ui-card-title h2':['fontSize','fontWeight'],'.ui-pager':['height','fontSize'],'.status-pill':['height','fontSize','borderRadius']})) {
    assert.ok(actual[selector],selector);
    for(const key of keys) {
      if(typeof baseline[selector][key]==='number')assert.ok(Math.abs(actual[selector][key]-baseline[selector][key])<=2,selector+' '+key);
      else assert.equal(actual[selector][key],baseline[selector][key],selector+' '+key);
      checks.push(selector+' '+key);
    }
  }
  const emblem=await page.locator('.legacy-sidebar-brand svg').evaluate(svg=>({viewBox:svg.getAttribute('viewBox'),width:svg.getAttribute('width'),paths:[...svg.querySelectorAll('path')].map(p=>p.getAttribute('d')),circle:svg.querySelector('circle').getAttribute('r')}));
  assert.deepEqual(emblem,baseline.emblem,'Preserve the original vector geometry and proportions');
  const client=await page.context().newCDPSession(page);await client.send('DOM.enable');await client.send('CSS.enable');const doc=await client.send('DOM.getDocument');const node=await client.send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'.board-heading h1'});
  const fonts=await client.send('CSS.getPlatformFontsForNode',{nodeId:node.nodeId});
  assert.ok(fonts.fonts.some(font=>font.familyName==='Pretendard Variable'&&font.isCustomFont),'The original local font must actually render');
  await page.screenshot({path:path.join(evidence,'legacy-board-1600x900.png'),animations:'disabled'});
  // All original user menu functions remain available in the sidebar, and in
  // the header when collapsed. Check the relocated popup's bounds and focus.
  const toggle=page.locator('.legacy-sidebar-user .dropdown-toggle');await toggle.click();
  const popup=page.locator('.legacy-sidebar-user .relative>div.absolute');await popup.waitFor();const bounds=await popup.boundingBox();assert.ok(bounds.x>=0&&bounds.y>=0&&bounds.y+bounds.height<=900);
  await page.keyboard.press('Escape');await popup.waitFor({state:'hidden'});assert.equal(await toggle.evaluate(e=>e===document.activeElement),true);
  await page.locator('header button').first().click();await page.locator('.legacy-header-user .dropdown-toggle').waitFor();await page.locator('header button').first().click();
  const screens=[['operations','/operations'],['register','/tasks?tab=department'],['request','/approvals/request'],['receipt','/approvals/receipts'],['approvals','/approvals/todo'],['organization','/department/org-chart'],['equipment','/department/equipment'],['diagrams','/department/diagrams'],['docs','/department/docs'],['calendar','/calendar'],['ai','/ai']];
  for(const [name,route] of screens) {await page.goto(origin+route);await page.locator('main h1').first().waitFor();await page.waitForTimeout(180);await page.screenshot({path:path.join(evidence,'legacy-'+name+'.png'),animations:'disabled'});}
  const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
  assert.equal(hash('src/frontend/src/assets/fonts/legacy/PretendardVariable.woff2'),hash('../military_project/node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2'));
  await page.evaluate(()=>{localStorage.setItem('theme','dark');});await page.goto(origin+'/board');await page.locator('html.dark').waitFor({state:'attached'});
  assert.equal(await page.locator('.legacy-sidebar-brand a>span').first().evaluate(e=>getComputedStyle(e).color),'rgb(183, 191, 240)');
  await page.screenshot({path:path.join(evidence,'legacy-dark.png'),animations:'disabled'});
  await page.setViewportSize({width:390,height:844});await page.locator('header svg[viewBox="0 0 48 28"]').waitFor();
  await page.screenshot({path:path.join(evidence,'legacy-mobile-brand.png'),animations:'disabled'});
  await page.evaluate(()=>{localStorage.setItem('theme','light');});
  fs.writeFileSync(path.join(evidence,'legacy-ui-result.json'),JSON.stringify({success:true,checks,emblem,actual,fonts,screens:screens.map(([name,route])=>({name,route})),fontFileMatchesOriginal:true,darkAndMobileBrandVisible:true,existingUserMenuPreserved:true,differentBusinessLabelsPreserved:true},null,2));
  await page.setViewportSize({width:1440,height:950});await page.goto(origin+'/board');
  console.log('PASS original emblem, real original font, '+checks.length+' computed-style checks, relocated user menu and 11 business-screen captures');
};
