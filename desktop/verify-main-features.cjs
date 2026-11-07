const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Latest-main support screens remain reachable alongside the simplified menus.
// This checks the real AI-off demo UI; enabled AI/assist behavior is tested by
// the backend FakeOllama suites, not presented as real-model inference.
module.exports = async function verifyMainFeatures(page,origin,evidence) {
  const screens=[
    '/approvals/request','/approvals/new','/directives','/duty/edit','/ict-status','/calendar',
    '/department/equipment','/department/diagrams','/department/docs','/department/people',
    '/admin/work-types','/admin/personnel','/admin/equipment','/admin/approval-routes',
  ];
  const visits=[];
  for(const route of screens) {
    await page.goto(origin+route);
    await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).waitFor();
    await page.locator('main h1').waitFor();
    assert.equal(new URL(page.url()).pathname,route);
    visits.push({route,heading:await page.locator('main h1').innerText()});
  }
  await page.goto(origin+'/tasks?tab=department');
  await page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true}).waitFor();
  await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();
  const panel=page.locator('#assist-panel');
  await panel.waitFor();
  await panel.getByText('현재 이 서버에서는 지원하지 않는 기능입니다.',{exact:true}).waitFor();
  await page.locator('main').getByRole('link',{name:'우리 부서 업무 등록',exact:true}).click();
  await page.getByRole('heading',{level:1,name:'우리 부서 업무 등록',exact:true}).waitFor();
  await panel.waitFor();
  await page.locator('aside').first().getByRole('link',{name:'우리 부서 업무',exact:true}).locator('xpath=self::*[@aria-current="page"]').waitFor();
  await page.waitForTimeout(300);
  await page.screenshot({animations:'disabled',path:path.join(evidence,'latest-main-assist-and-registration.png')});
  await page.locator('header').getByRole('button',{name:'AI 업무지원',exact:true}).click();
  await panel.waitFor({state:'hidden'});
  fs.writeFileSync(path.join(evidence,'latest-main-ui-result.json'),JSON.stringify({
    visits,assistPanelPersistsAcrossRegistrationNavigation:true,aiOffStateVerified:true,realModelInferenceTested:false,
  },null,2));
  console.log('PASS latest-main support-screen routes, AI support header/panel and integrated registration navigation');
};
