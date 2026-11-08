const {_electron}=require('playwright');const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const [reference,output]=process.argv.slice(2).map(p=>path.resolve(p));
(async()=>{
  fs.mkdirSync(output,{recursive:true});const profile=path.join(output,'isolated-profile');
  const env={...process.env,OMNIS_E2E_DATA:profile};delete env.ELECTRON_RUN_AS_NODE;
  const app=await _electron.launch({executablePath:path.join(reference,'node_modules/electron/dist/electron.exe'),args:[path.join(reference,'out/main/index.js')],cwd:reference,env,timeout:60000});
  const page=await app.firstWindow();page.setDefaultTimeout(20000);
  try {
    assert.equal(path.resolve(await app.evaluate(({app})=>app.getPath('userData'))),profile);
    await page.setViewportSize({width:1600,height:900});await page.getByRole('button',{name:'관리자 로그인',exact:true}).waitFor();
    await page.screenshot({path:path.join(output,'login-1600x900.png'),animations:'disabled'});
    await page.getByRole('button',{name:'관리자 로그인',exact:true}).click();await page.locator('.dash').waitFor();await page.evaluate(()=>document.fonts.ready);
    await page.screenshot({path:path.join(output,'board-1600x900.png'),animations:'disabled'});
    const measure=await page.evaluate(()=>Object.fromEntries(['body','.sidebar','.sb-brand strong','.ui-page-header h1','.metric','.metric-label','.metric-value','.ui-card-head','.ui-card-title h2','.ui-pager','.status-pill'].map(selector=>{
      const e=document.querySelector(selector==='.status-pill'?'.dashboard-grid .status-pill':selector);if(!e)return[selector,null];const c=getComputedStyle(e),r=e.getBoundingClientRect();
      return[selector,{fontFamily:c.fontFamily,fontSize:c.fontSize,fontWeight:c.fontWeight,borderRadius:c.borderRadius,padding:c.padding,height:r.height,width:r.width,color:c.color,background:c.backgroundColor}];
    })));
    const client=await page.context().newCDPSession(page);await client.send('DOM.enable');await client.send('CSS.enable');
    const doc=await client.send('DOM.getDocument');const node=await client.send('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'.ui-page-header h1'});
    measure.actualFonts=await client.send('CSS.getPlatformFontsForNode',{nodeId:node.nodeId});
    measure.emblem=await page.locator('.brand-mark svg').evaluate(svg=>({viewBox:svg.getAttribute('viewBox'),width:svg.getAttribute('width'),paths:[...svg.querySelectorAll('path')].map(p=>p.getAttribute('d')),circle:svg.querySelector('circle').getAttribute('r')}));
    fs.writeFileSync(path.join(output,'computed.json'),JSON.stringify(measure,null,2));
    await page.getByRole('link',{name:'운영현황',exact:true}).click();await page.locator('h1').filter({hasText:'운영현황'}).waitFor();
    await page.screenshot({path:path.join(output,'operations-1600x900.png'),animations:'disabled'});
    // Convert the original wing/star geometry to a Windows square icon tile.
    const svg=fs.readFileSync(path.resolve('src/frontend/public/images/logo/logo-icon.svg'),'utf8');
    const png=await page.evaluate(async svg=>{const canvas=document.createElement('canvas');canvas.width=canvas.height=256;const c=canvas.getContext('2d');c.fillStyle='#e4e7f6';c.beginPath();c.roundRect(0,0,256,256,50);c.fill();const image=new Image();image.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg);await image.decode();c.drawImage(image,25,68,206,120);return canvas.toDataURL('image/png').split(',')[1];},svg);
    fs.writeFileSync(path.join(output,'original-emblem-icon.png'),Buffer.from(png,'base64'));
    console.log('PASS isolated original UI capture, computed styles, actual font and emblem rendering');
  }catch(error){await page.screenshot({path:path.join(output,'capture-failure.png')}).catch(()=>{});throw error;}finally{await app.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
