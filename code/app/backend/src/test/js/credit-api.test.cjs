const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/static/assets/credit-api.js'), 'utf8');
function client(responses) {
    const calls = [];
    const redirects = [];
    const context = {window: {location: {assign: url => redirects.push(url)}}, document: {documentElement: {lang: 'en'}},
        Intl, fetch: async (url, options) => {
            calls.push({url, ...options});
            const response = responses.shift();
            if (response instanceof Error) throw response;
            assert.ok(response, 'Unexpected extra HTTP call');
            return response;
        }};
    vm.runInNewContext(source, context);
    return {api: context.window.credit, calls, redirects};
}
const csrf = () => Response.json({headerName: 'X-XSRF-TOKEN', token: 'csrf-value'});
test('logout accepts followed HTML redirect without attempting JSON parsing', async () => {
    const {api, calls} = client([csrf(), new Response('<html>Sign in</html>', {headers: {'Content-Type': 'text/html'}})]);
    assert.equal(await api.request('/logout', {method: 'POST'}), null);
    assert.equal(calls[1].headers['X-XSRF-TOKEN'], 'csrf-value');
});
test('an authentication refresh retries the command with the original idempotency key', async () => {
    const {api, calls} = client([csrf(), new Response(null, {status: 401}), csrf(), new Response(null, {status: 204}), csrf(), Response.json({applicationId: 'id'}, {status: 201})]);
    const response = await api.request('/api/credit/applications', {method: 'POST', headers: {'Idempotency-Key': 'stable-key'}, body: '{}'});
    assert.equal(response.applicationId, 'id');
    assert.deepEqual(calls.filter(call => call.url === '/api/credit/applications').map(call => call.headers['Idempotency-Key']), ['stable-key', 'stable-key']);
    assert.equal(calls.filter(call => call.url === '/auth/refresh').length, 1);
});
test('an uncertain network failure never repeats the mutation automatically', async () => {
    const {api, calls} = client([csrf(), new Error('network lost')]);
    await assert.rejects(api.request('/api/credit/applications', {method: 'POST'}), /Connection failed/);
    assert.equal(calls.length, 2);
});
test('an expired refresh session navigates to login after one attempt', async () => {
    const {api, calls, redirects} = client([new Response(null, {status: 401}), csrf(), new Response(null, {status: 401})]);
    await assert.rejects(api.request('/api/credit/applications'), /sign in/);
    assert.deepEqual(redirects, ['/login']);
    assert.equal(calls.length, 3);
});

test('parallel expired reads share one refresh-token rotation', async () => {
    const {api, calls} = client([new Response(null, {status: 401}), new Response(null, {status: 401}), csrf(),
        new Response(null, {status: 204}), Response.json({ok: true}), Response.json({ok: true})]);
    const results = await Promise.all([api.request('/api/credit/applications/id'), api.request('/api/credit/companies')]);
    assert.ok(results.every(result => result.ok));
    assert.equal(calls.filter(call => call.url === '/auth/refresh').length, 1);
});
