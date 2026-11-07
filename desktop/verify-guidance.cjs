const assert = require('node:assert/strict');
const path = require('node:path');

module.exports = async function verifyGuidance(page,origin,evidence) {
  const help=page.locator('main').getByRole('button',{name:'업무 안내',exact:true});
  const draft=page.locator('main form textarea:visible').first();
  const text='업무 안내를 열고 닫아도 작성 중인 요청을 유지하는 합성 검사';
  await draft.fill(text);
  await help.click();
  const dialog=page.getByRole('dialog',{name:'업무 안내',exact:true});
  await dialog.waitFor();
  await dialog.locator('dt').first().waitFor();
  assert.equal(new URL(page.url()).pathname,'/approvals/request');
  await dialog.getByRole('searchbox').fill('전화');
  await dialog.locator('dt').filter({hasText:'전화'}).first().waitFor();
  await page.waitForTimeout(200);
  await page.screenshot({animations:'disabled',path:path.join(evidence,'contextual-guidance.png')});
  await dialog.getByRole('button',{name:'닫기',exact:true}).last().click();
  await dialog.waitFor({state:'hidden'});
  assert.equal(await draft.inputValue(),text);
  assert.ok(await help.evaluate(element=>element===document.activeElement),'Closing help returns focus to its opener');

  // Read-only guidance failure/retry must not discard the request either.
  const endpoint=origin+'/api/work/types/all';
  await page.route(endpoint,route=>route.fulfill({status:503,json:{code:'TEST_UNAVAILABLE'}}));
  await help.click();
  await dialog.getByRole('alert').waitFor();
  await page.unroute(endpoint);
  await dialog.getByRole('button',{name:'다시 시도',exact:true}).click();
  await dialog.locator('dt').first().waitFor();
  await page.keyboard.press('Escape');
  await dialog.waitFor({state:'hidden'});
  assert.equal(await draft.inputValue(),text);
  await draft.fill('');
  console.log('PASS contextual guidance search, input preservation, focus return, read-only failure/retry and Escape close');
};
