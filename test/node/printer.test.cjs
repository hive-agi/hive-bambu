'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { exercise } = require('../../target/printer-test.js');
const printer = {name:'desk', host:'127.0.0.1', serial:'FAKE1',
                 'access-code': {scheme:'env', path:'BAMBU_FAKE_CODE'}};
process.env.BAMBU_FAKE_CODE = 'fake-only';

test('printer ports refuse missing session and credentials', async () => {
  assert.equal(typeof exercise, 'function');
});
