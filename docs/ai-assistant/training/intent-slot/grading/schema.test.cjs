const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Exercise the actual browser helpers without starting the DOM application.
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8').split('const $ =')[0];
const context = vm.createContext({});
vm.runInContext(source, context);
const schema = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'intent-slot.schema.json')));

test('grading lists all intents and only offers concept for expressed needs', () => {
  const ids = context.intentIds(schema);
  assert.equal(ids.length, 8);
  assert.equal(new Set(ids).size, 8);
  for (const intent of ids) {
    const slots = context.slotProperties(schema, intent);
    assert.equal('concept' in slots, intent === 'express_need', intent);
    assert.ok(slots.location);
  }
  assert.equal(Object.keys(context.slotProperties(schema, 'unknown')).length, 0);
});

test('archived packages with a flat schema remain reviewable', () => {
  const legacy = schema.oneOf[0];
  assert.equal(context.intentIds(legacy).length, 7);
  assert.ok(context.slotProperties(legacy, 'set_device_state').state);
});
